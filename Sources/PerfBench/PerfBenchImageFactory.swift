import CoreGraphics

enum PerfBenchImageFactory {
    static func thumbnail() throws -> CGImage {
        guard let context = CGContext(
            data: nil,
            width: 8,
            height: 8,
            bitsPerComponent: 8,
            bytesPerRow: 32,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ), let image = context.makeImage() else {
            throw PerfBenchError.thumbnailImageUnavailable
        }
        return image
    }
}
