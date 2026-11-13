import Foundation

// Pure calculation shared with the executable tests. Distances must originate in
// running workouts; a whole-day walking/running statistic is never an input.
enum RunningDistancePolicy {
    struct Run {
        let id: String
        let start: Date
        let end: Date
        let meters: Double
    }
    struct Result {
        let meters: Double?
        let overlappingWorkouts: Bool
    }

    static func total(_ runs: [Run]) -> Result {
        // Watch + an importing app can both publish the same workout under different
        // UUIDs. Never add overlapping workouts. Prefer the more complete interval,
        // then a stable UUID, independent of HealthKit's query order.
        let sorted = runs.filter { $0.end > $0.start && $0.meters.isFinite && $0.meters > 0 }
            .sorted {
                let lhs = $0.end.timeIntervalSince($0.start), rhs = $1.end.timeIntervalSince($1.start)
                return lhs == rhs ? $0.id < $1.id : lhs > rhs
            }
        var accepted: [Run] = []
        var ids = Set<String>()
        var overlap = false
        for run in sorted {
            guard ids.insert(run.id).inserted else { continue }
            if accepted.contains(where: { max($0.start, run.start) < min($0.end, run.end) }) {
                overlap = true
                continue
            }
            accepted.append(run)
        }
        let sum = accepted.reduce(0) { $0 + $1.meters }
        return Result(meters: sum.isFinite && sum > 0 ? (sum * 100).rounded() / 100 : nil,
                      overlappingWorkouts: overlap)
    }
}
