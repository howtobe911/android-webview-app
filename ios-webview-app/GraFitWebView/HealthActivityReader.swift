import Foundation
import HealthKit

// All state is confined to the main queue. HealthKit callbacks are marshalled there.
final class HealthActivityReader {
    struct Result {
        var values: [String: Double] = [:]
        var unavailable: Set<String> = []
        var warnings: Set<String> = []
    }
    private let store: HKHealthStore
    private var queries: [UUID: HKQuery] = [:]
    private var generation = UUID()
    init(store: HKHealthStore) { self.store = store }
    deinit { for query in queries.values { store.stop(query) } }

    func cancel() {
        generation = UUID()
        for query in queries.values { store.stop(query) }
        queries.removeAll()
    }

    func read(start: Date, end: Date, completion: @escaping (Result) -> Void) {
        cancel()
        let token = generation
        var result = Result()
        var remaining = 2
        let finish: () -> Void = { [weak self] in
            guard self?.generation == token else { return }
            remaining -= 1
            if remaining == 0 { completion(result) }
        }
        guard let steps = HKQuantityType.quantityType(forIdentifier: .stepCount),
              let distance = HKQuantityType.quantityType(forIdentifier: .distanceWalkingRunning) else {
            result.unavailable = ["steps", "meters"]; completion(result); return
        }
        let automatic = NSCompoundPredicate(notPredicateWithSubpredicate:
            HKQuery.predicateForObjects(withMetadataKey: HKMetadataKeyWasUserEntered, allowedValues: [true]))
        let stepPredicate = NSCompoundPredicate(andPredicateWithSubpredicates: [automatic,
            HKQuery.predicateForSamples(withStart: start, end: end, options: [.strictStartDate, .strictEndDate])])
        let stepID = UUID()
        let stepQuery = HKStatisticsQuery(quantityType: steps, quantitySamplePredicate: stepPredicate, options: .cumulativeSum) { [weak self] _, statistics, error in
            let count = statistics?.sumQuantity()?.doubleValue(for: .count())
            DispatchQueue.main.async {
                guard let self, self.generation == token else { return }
                self.queries.removeValue(forKey: stepID)
                if error != nil { result.unavailable.insert("steps") }
                else if let count, count.isFinite, count > 0 { result.values["steps"] = count.rounded() }
                finish()
            }
        }
        queries[stepID] = stepQuery
        store.execute(stepQuery)

        let workoutPredicate = NSCompoundPredicate(andPredicateWithSubpredicates: [automatic,
            HKQuery.predicateForWorkouts(with: .running),
            HKQuery.predicateForSamples(withStart: start, end: end, options: [])])
        let workoutID = UUID()
        let workoutQuery = HKSampleQuery(sampleType: .workoutType(), predicate: workoutPredicate, limit: 513, sortDescriptors: nil) { [weak self] _, samples, error in
            DispatchQueue.main.async {
                guard let self, self.generation == token else { return }
                self.queries.removeValue(forKey: workoutID)
                guard error == nil, let workouts = samples as? [HKWorkout], workouts.count <= 512 else {
                    result.unavailable.insert("meters"); finish(); return
                }
                var candidates: [RunningDistancePolicy.Run] = []
                // Only boundary workouts require another query. Run them sequentially
                // so a large import cannot create hundreds of simultaneous queries.
                var boundary: [HKWorkout] = []
                for workout in workouts {
                    guard workout.workoutActivityType == .running,
                          workout.endDate > workout.startDate,
                          !workout.workoutActivities.contains(where: { $0.workoutConfiguration.activityType != .running }) else {
                        result.warnings.insert("mixed_workout_omitted"); continue
                    }
                    if workout.startDate >= start && workout.endDate <= end {
                        let meters = workout.statistics(for: distance)?.sumQuantity()?.doubleValue(for: .meter())
                            ?? workout.totalDistance?.doubleValue(for: .meter())
                        if let meters, meters.isFinite, meters > 0 {
                            candidates.append(.init(id: workout.uuid.uuidString, start: workout.startDate, end: workout.endDate, meters: meters))
                        } else { result.warnings.insert("workout_distance_unavailable") }
                    } else { boundary.append(workout) }
                }
                self.readBoundaries(boundary, index: 0, token: token, start: start, end: end,
                                    distance: distance, automatic: automatic, candidates: candidates, warnings: []) { runs, warnings in
                    result.warnings.formUnion(warnings)
                    let total = RunningDistancePolicy.total(runs)
                    if let meters = total.meters { result.values["meters"] = meters }
                    if total.overlappingWorkouts { result.warnings.insert("overlapping_workouts_omitted") }
                    finish()
                }
            }
        }
        queries[workoutID] = workoutQuery
        store.execute(workoutQuery)
    }
    private func readBoundaries(_ workouts: [HKWorkout], index: Int, token: UUID, start: Date, end: Date,
                                distance: HKQuantityType, automatic: NSPredicate,
                                candidates: [RunningDistancePolicy.Run], warnings: Set<String>,
                                completion: @escaping ([RunningDistancePolicy.Run], Set<String>) -> Void) {
        guard generation == token else { return }
        guard index < workouts.count else { completion(candidates, warnings); return }
        let workout = workouts[index]
        let from = max(start, workout.startDate), to = min(end, workout.endDate)
        let predicate = NSCompoundPredicate(andPredicateWithSubpredicates: [automatic,
            HKQuery.predicateForObjects(from: workout),
            HKQuery.predicateForSamples(withStart: from, end: to, options: [.strictStartDate, .strictEndDate])])
        let id = UUID()
        let query = HKSampleQuery(sampleType: distance, predicate: predicate, limit: 10001, sortDescriptors: nil) { [weak self] _, samples, error in
            DispatchQueue.main.async {
                guard let self, self.generation == token else { return }
                self.queries.removeValue(forKey: id)
                var updated = candidates, issues = warnings
                // No fabricated proportional distance at midnight: only associated
                // samples fully within this UTC window. The omitted edge is explicit.
                issues.insert("boundary_workout_partial")
                if error == nil, let samples = samples as? [HKQuantitySample], samples.count <= 10000 {
                    let portions: [RunningDistancePolicy.Run] = samples.compactMap { sample in
                        let value = sample.quantity.doubleValue(for: .meter())
                        guard sample.startDate >= from, sample.endDate <= to,
                              sample.endDate > sample.startDate, value.isFinite, value > 0 else { return nil }
                        return .init(id: sample.uuid.uuidString, start: sample.startDate, end: sample.endDate, meters: value)
                    }
                    let total = RunningDistancePolicy.total(portions)
                    if total.overlappingWorkouts { issues.insert("overlapping_distance_samples_omitted") }
                    if var meters = total.meters {
                        if let whole = workout.statistics(for: distance)?.sumQuantity()?.doubleValue(for: .meter())
                            ?? workout.totalDistance?.doubleValue(for: .meter()), whole.isFinite, whole > 0 { meters = min(meters, whole) }
                        updated.append(.init(id: workout.uuid.uuidString, start: from, end: to, meters: meters))
                    }
                } else { issues.insert("boundary_distance_unavailable") }
                self.readBoundaries(workouts, index: index + 1, token: token, start: start, end: end,
                                    distance: distance, automatic: automatic, candidates: updated, warnings: issues, completion: completion)
            }
        }
        queries[id] = query
        store.execute(query)
    }

}
