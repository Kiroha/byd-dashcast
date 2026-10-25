import QtQuick 2.15

// This component supplies decoration only. Every instrument, unit, alert and
// telltale remains an OEM item driven directly by the native DataSource.
Item {
    id: dashboard
    width: 1920
    height: 720
    property bool night: false
    readonly property color background: night ? "#101820" : "#e9f0f4"
    readonly property color panel: night ? "#1a2935" : "#ffffff"
    readonly property color outline: night ? "#345367" : "#b5c9d5"
    readonly property color accent: night ? "#49d5de" : "#006b79"

    // The centre rectangle is deliberately absent: the Android surface is
    // below this native QML scene and must remain visible through that hole.
    Rectangle { x: 0; y: 0; width: 1920; height: 256; color: dashboard.background }
    Rectangle { x: 0; y: 256; width: 464; height: 320; color: dashboard.background }
    Rectangle { x: 1216; y: 256; width: 704; height: 320; color: dashboard.background }
    Rectangle { x: 0; y: 576; width: 1920; height: 144; color: dashboard.background }
    // OEM artwork includes fixed white variants, including the Korean seats.
    // Keep their contrast on the same dark strip in day and night modes.
    Rectangle { x: 16; y: 80; width: 1888; height: 168; radius: 10; color: "#142634" }
    Rectangle {
        x: 32; y: 256; width: 400; height: 312; radius: 16
        color: dashboard.panel; border.color: dashboard.outline
    }
    Rectangle {
        x: 1248; y: 256; width: 640; height: 312; radius: 16
        color: dashboard.panel; border.color: dashboard.outline
    }
    Rectangle {
        x: 456; y: 248; width: 768; height: 336; radius: 12
        color: "transparent"; border.color: dashboard.accent; border.width: 3
    }
    Rectangle { x: 48; y: 421; width: 368; height: 1; color: dashboard.outline }
    Text {
        x: 36; y: 16; text: "DashCast"; color: dashboard.accent
        font.pixelSize: 28; font.bold: true
    }
}
