import XCTest
import ollyLayouts
@testable import ollyDSL

final class AnimationTests: XCTestCase {
    func testAnimationBuilderUsesDefaultsAndOverrides() {
        let animation = Animation {
            duration(120.ms)
            curve(.linear)
            reduceMotion(.neverAnimate)
        }

        XCTAssertEqual(animation.duration.milliseconds, 120)
        XCTAssertEqual(animation.duration.seconds, 0.12)
        XCTAssertEqual(animation.curve, .linear)
        XCTAssertEqual(animation.reduceMotion, .neverAnimate)
    }

    func testConfigStoresGlobalAnimation() {
        let animation = Animation {
            duration(300.ms)
            curve(.easeInOut)
            reduceMotion(.alwaysAnimate)
        }
        let config = Config {
            Animation {
                duration(300.ms)
                curve(.easeInOut)
                reduceMotion(.alwaysAnimate)
            }
        }

        XCTAssertEqual(config.animation, animation)
    }

    func testEngineAnimationOverridesGlobalAnimation() {
        let global = Animation {
            duration(200.ms)
            curve(.easeOut)
        }
        let override = Animation {
            duration(90.ms)
            curve(.linear)
        }
        let config = Config {
            Animation {
                duration(200.ms)
                curve(.easeOut)
            }
            Engines {
                EngineDeclaration.niriScroll.animated(override)
                EngineDeclaration.bsp
            }
        }

        XCTAssertEqual(config.animation(for: NiriScrollLayoutEngine.engineID), override)
        XCTAssertEqual(config.animation(for: BSPLayoutEngine.engineID), global)
    }

    func testEngineAnimationCodableRoundTrips() throws {
        let declaration = EngineDeclaration.niriScroll.animated(
            Animation {
                duration(160.ms)
                curve(.easeInOut)
            }
        )

        let data = try JSONEncoder().encode(declaration)
        let decoded = try JSONDecoder().decode(EngineDeclaration.self, from: data)

        XCTAssertEqual(decoded, declaration)
    }
}
