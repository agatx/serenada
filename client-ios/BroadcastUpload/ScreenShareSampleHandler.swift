import SerenadaBroadcastExtensionSupport

/// Principal class for the Serenada reference app's broadcast upload extension.
///
/// All behavior lives in the SDK's `SerenadaBroadcastSampleHandler`; this thin
/// subclass exists only so `NSExtensionPrincipalClass =
/// $(PRODUCT_MODULE_NAME).ScreenShareSampleHandler` resolves to a class in the
/// extension's own module. The shared App Group is read from the
/// `SerenadaBroadcastAppGroupIdentifier` Info.plist key.
final class ScreenShareSampleHandler: SerenadaBroadcastSampleHandler {}
