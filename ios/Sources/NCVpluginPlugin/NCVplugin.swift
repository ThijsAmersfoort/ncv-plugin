import Foundation
import Capacitor
import UniformTypeIdentifiers

@objc public class NCVplugin: NSObject {
    enum ContentType: Int {
        case string
        case url
        case image
        case html
    }

    public enum ClipboardError: LocalizedError {
        case invalidURL, invalidImage, invalidHtml

        public var errorDescription: String? {
            switch self {
            case .invalidURL:
                return "Unable to form URL"
            case .invalidImage:
                return "Unable to encode image"
            case .invalidHtml:
                return "Unable to encode Html"
            }
        }
    }

    func plainTextFromHTML(_ html: String) -> String? {
        guard Thread.isMainThread else {
            // Dispatch synchroon naar main thread
            return DispatchQueue.main.sync { plainTextFromHTML(html) }
        }
        
        guard let data = html.data(using: .utf8) else { return nil }
        
        let options: [NSAttributedString.DocumentReadingOptionKey: Any] = [
            .documentType: NSAttributedString.DocumentType.html,
            .characterEncoding: String.Encoding.utf8.rawValue
        ]
        
        return try? NSAttributedString(
            data: data,
            options: options,
            documentAttributes: nil
        ).string
    }

    func ClipboardWrite(content: String, ofType type: ContentType) -> Result<Void, Error> {
        switch type {
        case ContentType.string:
            UIPasteboard.general.string = content
            return .success(())
        case ContentType.url:
            if let url = URL(string: content) {
                UIPasteboard.general.url = url
                return .success(())
            } else {
                return .failure(ClipboardError.invalidURL)
            }
        case ContentType.image:
            if let data = Data.capacitor.data(base64EncodedOrDataUrl: content), let image = UIImage(data: data) {
                CAPLog.print("Loaded image", image.size.width, image.size.height)
                UIPasteboard.general.image = image
                return .success(())
            } else {
                return .failure(ClipboardError.invalidImage)
            }
        case ContentType.html:
            CAPLog.print("HTML data")
            let wrappedHTML: String
            if content.lowercased().contains("<html") {
                wrappedHTML = content
            } else {
                wrappedHTML = """
                <html><head><meta charset="UTF-8"></head><body>\(content)</body></html>
                """
            }
            
            /*guard let htmlData = wrappedHTML.data(using: .utf8) else {
                return .failure(ClipboardError.invalidHtml)
            }
            //CAPLog.print(wrappedHTML,htmlData)*/
            
            // Fallback plaintext — gebruik de originele content als plainTextFromHTML faalt
            let plainText = plainTextFromHTML(wrappedHTML) ?? wrappedHTML.replacingOccurrences(
                of: "<[^>]+>",
                with: "",
                options: .regularExpression
            )
    
            // Key-value pairs of Type Identifier -> Data/String
            let item: [String: Any] = [
                UTType.html.identifier: wrappedHTML,
                UTType.plainText.identifier: plainText  // Nu gegarandeerd een String, niet Optional
            ]
            
            UIPasteboard.general.items = [item]
            // Forceer synchronisatie door direct terug te lezen
            _ = UIPasteboard.general.value(forPasteboardType: UTType.html.identifier)
            return .success(())
        }
    }

    func ClipboardRead() -> [String: Any] {
        let pasteboard = UIPasteboard.general

        var htmlString: String? = nil
        var plainString: String? = nil
        
        // 1. Read Plain Text (the standard way)
        if pasteboard.hasStrings {
            plainString = pasteboard.string
        }
        // Check for HTML first (more specific)
        if let data = pasteboard.value(forPasteboardType: UTType.html.identifier) as? Data {
            htmlString = String(data: data, encoding: .utf8)
            return [
                "value": htmlString,
                "type": "text/html"
            ]
        }
        
        // Then check for plain text
        if plainString != nil {
            return [
                "value": plainString,
                "type": "text/plain"
            ]
        }

        if let url = pasteboard.url {
            return [
                "value": url.absoluteString,
                "type": "text/plain"
            ]
        }

        if let image = pasteboard.image {
            let data = image.pngData()
            if let base64 = data?.base64EncodedString() {
                return [
                    "value": "data:image/png;base64," + base64,
                    "type": "image/png"
                ]
            }
        }

        return [:]
    }
}
