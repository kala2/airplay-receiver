// Renders the 320x180 Android TV launcher banner (xhdpi) with AppKit.
// Usage: swift scripts/make-banner.swift <output.png>
// Kept as a script so the PNG can be regenerated instead of hand-edited.
import AppKit

let outPath = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "banner.png"
let w = 320, h = 180

guard let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8,
    samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
) else { fatalError("bitmap") }

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

// Background: deep blue-grey gradient, calm on a dark TV home screen.
let bg = NSGradient(starting: NSColor(calibratedRed: 0.07, green: 0.09, blue: 0.13, alpha: 1),
                    ending: NSColor(calibratedRed: 0.11, green: 0.20, blue: 0.32, alpha: 1))!
bg.draw(in: NSRect(x: 0, y: 0, width: w, height: h), angle: 30)

// Screen-with-triangle glyph (generic "mirroring" symbol).
let accent = NSColor(calibratedRed: 0.45, green: 0.75, blue: 1.0, alpha: 1)
accent.setStroke()
let screen = NSBezierPath(roundedRect: NSRect(x: 26, y: 70, width: 64, height: 46), xRadius: 6, yRadius: 6)
screen.lineWidth = 5
screen.stroke()
accent.setFill()
let tri = NSBezierPath()
tri.move(to: NSPoint(x: 58, y: 84))
tri.line(to: NSPoint(x: 76, y: 56))
tri.line(to: NSPoint(x: 40, y: 56))
tri.close()
NSColor(calibratedRed: 0.09, green: 0.14, blue: 0.22, alpha: 1).setStroke()
tri.lineWidth = 6
tri.stroke()
tri.fill()

func draw(_ s: String, size: CGFloat, weight: NSFont.Weight, color: NSColor, at p: NSPoint) {
    let attrs: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: size, weight: weight),
        .foregroundColor: color,
    ]
    s.draw(at: p, withAttributes: attrs)
}
draw("AirPlay", size: 34, weight: .semibold, color: .white, at: NSPoint(x: 108, y: 88))
draw("Receiver", size: 22, weight: .regular, color: NSColor(white: 0.82, alpha: 1), at: NSPoint(x: 109, y: 60))

NSGraphicsContext.restoreGraphicsState()

guard let png = rep.representation(using: .png, properties: [:]) else { fatalError("png") }
try! png.write(to: URL(fileURLWithPath: outPath))
print("wrote \(outPath)")
