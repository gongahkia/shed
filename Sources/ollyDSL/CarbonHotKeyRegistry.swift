import Carbon.HIToolbox
import Foundation

public struct CarbonHotKeyRegistration: Equatable, Sendable {
    public let id: UInt32
    public let signature: UInt32
    public let keyCode: UInt32
    public let modifiers: UInt32
    public let action: Action

    public init(id: UInt32, signature: UInt32, keyCode: UInt32, modifiers: UInt32, action: Action) {
        self.id = id
        self.signature = signature
        self.keyCode = keyCode
        self.modifiers = modifiers
        self.action = action
    }

    var eventHotKeyID: EventHotKeyID {
        EventHotKeyID(signature: signature, id: id)
    }
}

public enum CarbonHotKeyRegistryError: Error, Equatable {
    case registrationFailed(status: OSStatus, registration: CarbonHotKeyRegistration)
}

public final class CarbonHotKeyRegistry {
    public static let defaultSignature: UInt32 = 0x6F6C6C79

    private var registeredRefs: [EventHotKeyRef] = []

    public init() {}

    deinit {
        unregisterAll()
    }

    public func register(_ keybinds: Keybinds) throws {
        try register(keybinds.carbonRegistrations())
    }

    public func register(_ registrations: [CarbonHotKeyRegistration]) throws {
        for registration in registrations {
            var ref: EventHotKeyRef?
            let status = RegisterEventHotKey(
                registration.keyCode,
                registration.modifiers,
                registration.eventHotKeyID,
                GetApplicationEventTarget(),
                UInt32(kEventHotKeyNoOptions),
                &ref
            )
            guard status == noErr else {
                throw CarbonHotKeyRegistryError.registrationFailed(status: status, registration: registration)
            }
            if let ref {
                registeredRefs.append(ref)
            }
        }
    }

    public func unregisterAll() {
        for ref in registeredRefs {
            _ = UnregisterEventHotKey(ref)
        }
        registeredRefs.removeAll()
    }
}

public extension Keybinds {
    func carbonRegistrations(signature: UInt32 = CarbonHotKeyRegistry.defaultSignature) -> [CarbonHotKeyRegistration] {
        bindings.enumerated().map { index, keybind in
            CarbonHotKeyRegistration(
                id: UInt32(index + 1),
                signature: signature,
                keyCode: keybind.chord.key.rawValue,
                modifiers: keybind.chord.modifiers.carbonFlags,
                action: keybind.action
            )
        }
    }
}
