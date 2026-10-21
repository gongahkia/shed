// Companion to replay_native_smoke.py. Keys target the spawned viewer;
// mouse events use its focused window's screen coordinates.
import Foundation
import CoreGraphics
let args = CommandLine.arguments
let pid = pid_t(args[1])!
let command = args[2]
func key(_ code: CGKeyCode, _ down: Bool) {
    let event = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down)!
    if code == 56 { event.type = .flagsChanged; event.flags = down ? .maskShift : [] }
    event.postToPid(pid)
    usleep(150000)
}
func mouse(_ type: CGEventType, _ x: Double, _ y: Double, _ button: CGMouseButton) {
    let point = CGPoint(x:x,y:y)
    CGWarpMouseCursorPosition(point)
    let event = CGEvent(mouseEventSource:nil,mouseType:type,mouseCursorPosition:point,mouseButton:button)!
    event.post(tap: .cghidEventTap)
    usleep(120000)
}
switch command {
case "key": let code=CGKeyCode(args[3])!;key(code,true);key(code,false)
case "down": key(CGKeyCode(args[3])!,true)
case "up": key(CGKeyCode(args[3])!,false)
case "click":
    let x=Double(args[3])!,y=Double(args[4])!
    mouse(.leftMouseDown,x,y,.left);mouse(.leftMouseUp,x,y,.left)
case "drag":
    let x=Double(args[3])!,y=Double(args[4])!,dx=Double(args[5])!,dy=Double(args[6])!
    mouse(.rightMouseDown,x,y,.right)
    for step in 1...6 {let fraction=Double(step)/6.0;mouse(.rightMouseDragged,x+dx*fraction,y+dy*fraction,.right)}
    mouse(.rightMouseUp,x+dx,y+dy,.right)
case "scroll":
    CGWarpMouseCursorPosition(CGPoint(x:Double(args[3])!,y:Double(args[4])!))
    CGEvent(scrollWheelEvent2Source:nil,units:.line,wheelCount:1,wheel1:Int32(args[5])!,wheel2:0,wheel3:0)!.post(tap: .cghidEventTap)
    usleep(250000)
case "position":
    let point=CGEvent(source:nil)!.location;print("\(point.x) \(point.y)")
case "move": CGWarpMouseCursorPosition(CGPoint(x:Double(args[3])!,y:Double(args[4])!))
default: fatalError("unknown native smoke-test event")
}
