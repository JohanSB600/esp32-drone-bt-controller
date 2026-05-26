# ESP32 Drone BT Controller

Control básico por **Bluetooth clásico (SPP)** para un ESP32 (usando `BluetoothSerial`) desde **Android 12+**.

## Estructura
- `android-app/` → aplicación Android (Kotlin + Jetpack Compose)

## Requisitos
- Android Studio
- Teléfono Android 12+ (recomendado para pruebas con Bluetooth real)

## Cómo abrir y ejecutar
1. Abre Android Studio.
2. **Clone Repository** y clona este repo (o descarga el ZIP).
3. En Android Studio: **Open** → selecciona la carpeta `android-app/`.
4. Espera a que Gradle sincronice.
5. En tu teléfono:
   - Empareja el dispositivo Bluetooth llamado **`ESP32_Puerta`** desde Ajustes.
   - Activa Bluetooth.
6. Ejecuta la app en el teléfono.

## Comandos enviados
- `A` → Encender motores
- `B` → Apagar motores
- `C` → Abrir puerta (servo)
- `D` → Cerrar puerta (servo)
- `E` → Stop total

> Nota: iOS no soporta SPP de forma general. Este proyecto está pensado para Android.
