import Foundation

enum ReviewQuote {
    static func findMatches(in snapshot: String, quote: String) -> [TextRange] {
        guard !snapshot.isEmpty, !quote.isEmpty else {
            return []
        }
        let nsSnapshot = snapshot as NSString
        let totalLength = nsSnapshot.length
        var matches: [TextRange] = []
        var searchStart = 0

        while searchStart < totalLength {
            let searchRange = NSRange(location: searchStart, length: totalLength - searchStart)
            let found = nsSnapshot.range(of: quote, options: .literal, range: searchRange)
            if found.location == NSNotFound {
                break
            }
            matches.append(TextRange(start: found.location, end: found.location + found.length))
            searchStart = found.location + 1
        }
        return matches
    }
}
