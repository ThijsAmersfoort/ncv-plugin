import Foundation
import Capacitor

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(NCVpluginPlugin)
public class NCVpluginPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NCVpluginPlugin"
    public let jsName = "NCVplugin"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "info", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "ClipboardRead", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "ClipboardWrite", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openUrl", returnType: CAPPluginReturnPromise)
    ]
    private let implementation = NCVplugin()

    @objc func info(_ call: CAPPluginCall) {
        var platform = "ios"
        if ProcessInfo.processInfo.isiOSAppOnMac {
            platform = "mac"
        }
        let version = ProcessInfo.processInfo.operatingSystemVersionString
        call.resolve([
            "platform": platform,
            "version": version
        ])
    }

    @objc func ClipboardRead(_ call: CAPPluginCall) {
        let result = implementation.ClipboardRead()

        if !result.isEmpty {
            call.resolve(result)
        } else {
            call.reject("There is no data on the clipboard")
        }
    }

    @objc func ClipboardWrite(_ call: CAPPluginCall) {
        var result: Result<Void, Error>

        if let html = call.options["html"] as? String {
            result = implementation.ClipboardWrite(content: html, ofType: NCVplugin.ContentType.html)
        } else if let string = call.options["string"] as? String {
            result = implementation.ClipboardWrite(content: string, ofType: NCVplugin.ContentType.string)
        } else if let urlString = call.options["url"] as? String {
            result = implementation.ClipboardWrite(content: urlString, ofType: NCVplugin.ContentType.url)
        } else if let imageBase64 = call.options["image"] as? String {
            result = implementation.ClipboardWrite(content: imageBase64, ofType: NCVplugin.ContentType.image)
        } else {
            call.reject("No content provided")
            return
        }

        switch result {
        case .success:
            call.resolve()
        case .failure(let err):
            CAPLog.print(err.localizedDescription)
            call.reject(err.localizedDescription)
        }
    }

    @objc func openUrl(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url") else {
            call.reject("Must supply a URL")
            return
        }

        guard let url = URL.init(string: urlString) else {
            call.reject("Invalid URL")
            return
        }

        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:]) { (completed) in
                call.resolve([
                    "completed": completed
                ])
            }
        }
    }
}
