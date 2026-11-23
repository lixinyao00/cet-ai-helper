import AVFoundation
import Foundation

final class SpeechService {
    private let synthesizer = AVSpeechSynthesizer()

    @MainActor
    func speak(_ text: String) {
        let cleaned = TextMetrics.trimSpace(text)
        guard !cleaned.isEmpty else { return }
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
        let utterance = AVSpeechUtterance(string: cleaned)
        utterance.voice = AVSpeechSynthesisVoice(language: "en-US")
        synthesizer.speak(utterance)
    }

    @MainActor
    func stop() {
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
    }
}
