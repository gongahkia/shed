import CoreGraphics

extension CGRect {
    func withOrigin(_ origin: CGPoint) -> CGRect {
        CGRect(origin: origin, size: size)
    }

    func withSize(_ size: CGSize) -> CGRect {
        CGRect(origin: origin, size: size)
    }
}

extension CGPoint {
    func isWithin(_ threshold: CGFloat, of other: CGPoint) -> Bool {
        abs(x - other.x) < threshold && abs(y - other.y) < threshold
    }
}

extension CGSize {
    func isWithin(_ threshold: CGFloat, of other: CGSize) -> Bool {
        abs(width - other.width) < threshold && abs(height - other.height) < threshold
    }
}
