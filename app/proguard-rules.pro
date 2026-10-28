# R8 keep rules. Everything native code looks up by name must survive shrinking/renaming.

# NativeBridge: JNI_OnLoad finds the class by name and calls these static methods by name and
# signature (onServiceChanged, onVideoFrame, onMirrorState, onVideoSize, onAudioPacket,
# onAudioVolume, onAudioFlush), and the external functions are bound by name.
-keep class dev.personal.airplayrx.NativeBridge { *; }

# Any other native methods (none today) keep their names too.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
