// frontwin: prints "<windowId>\t<app name>" of the frontmost app's main window.
// Used by leoagent-server for the "front app" screen preview (PiP). Build: swiftc -O frontwin.swift -o frontwin
import AppKit
import CoreGraphics
let app = NSWorkspace.shared.frontmostApplication
let pid = app?.processIdentifier ?? -1
let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] ?? []
var id = 0
for w in list {
  if (w[kCGWindowOwnerPID as String] as? Int32) == pid, (w[kCGWindowLayer as String] as? Int) == 0,
     let b = w[kCGWindowBounds as String] as? [String: Any], (b["Width"] as? Double ?? 0) > 80 { id = w[kCGWindowNumber as String] as? Int ?? 0; break }
}
print("\(id)\t\(app?.localizedName ?? "")")
