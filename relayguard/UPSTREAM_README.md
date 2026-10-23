# RelayGuard-Android

نسخة Android مبنية على مشروع [MasterHttpRelayVPN-RUST](https://github.com/therealaleph/MasterHttpRelayVPN-RUST)، مع هوية RelayGuard وإعدادات مبسطة لوضع **Apps Script (MITM)** فقط.

## ما الذي تغيّر؟

- اسم التطبيق: **RelayGuard proxy**، مع أيقونة تطبيق مخصصة.
- الواجهة متاحة بالعربية والإنجليزية فقط.
- وضع التشغيل ثابت على **Apps Script (MITM)**؛ لا يظهر محدد أوضاع.
- يُكتشف `google_ip` تلقائيًا عند كل اتصال، ولا يظهر عنوان IP أو زر الاكتشاف.
- `front_domain` هو اسم SNI الوحيد المستخدم؛ أخفيت مجموعة SNI وأداة الاختبار.
- تمرير **جميع التطبيقات** هو الإعداد الثابت؛ أُخفيت خيارات تقسيم التطبيقات.
- إعدادات **Advanced** متاحة من قائمة منبثقة.
- دليل الاستخدام يشرح إعداد Apps Script وشهادة MITM والاتصال. تظهر نسبة التطوير إلى الرفاعي ورابطه في التطبيق.

## متطلبات البناء

| المكوّن | المطلوب |
|---|---|
| JDK | 17 |
| Android SDK Platform | API 34 |
| Android Build Tools | 34.0.0 |
| Android NDK | 26.3.11579264 |
| Rust | stable toolchain مع أهداف Android الأربعة أدناه |
| Gradle | 8.9 عبر Gradle Wrapper المرفق |
| Android Gradle Plugin / Kotlin | 8.5.0 / 2.0.0، مثبتان في ملفات المشروع |
| أداة الربط Rust–Android | `cargo-ndk` |

أهداف Rust اللازمة لبناء كل معماريات APK:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo install cargo-ndk --locked
```

اضبط `ANDROID_HOME` على مسار Android SDK، وثبّت `platforms;android-34` و`build-tools;34.0.0` و`ndk;26.3.11579264`، واضبط `ANDROID_NDK_HOME` على `$ANDROID_HOME/ndk/26.3.11579264`. يحتاج Gradle وCargo إلى اتصال بالإنترنت لتنزيل الاعتماديات.

## البناء

من جذر المستودع:

```bash
cd android
./gradlew assembleDebug
```

لإنشاء إصدار Release:

```bash
./gradlew assembleRelease
```

تُبنى المكتبات الأصلية تلقائيًا عبر `cargo-ndk` لأهداف `arm64-v8a` و`armeabi-v7a` و`x86_64` و`x86`. تضاف مكتبات JNI الناتجة إلى APK بواسطة Gradle؛ لا تُحفظ ملفات `.so` المولدة في Git.


## توقيع الإصدارات

لا يتضمن المستودع مفتاح توقيع خاصًا أو كلمة مرور. إصدار Release الناتج دون إعداد توقيع يكون غير موقع. لإنتاج APK موقع، مرّر بيانات **مفتاحك أنت** عبر متغيرات البيئة التالية عند البناء:

```text
ANDROID_KEYSTORE_PATH
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

لا ترفع ملف keystore أو كلمات المرور إلى Git. لاستخدام إعدادات Gradle بدلاً من متغيرات البيئة، تتوفر الخصائص `RELEASE_STORE_FILE` و`RELEASE_STORE_PASSWORD` و`RELEASE_KEY_ALIAS` و`RELEASE_KEY_PASSWORD`.

## إعداد التشغيل

يحتاج التطبيق إلى نشر Apps Script صالح، ورابط `/exec` أو Script ID، ومفتاح `auth_key` مطابق لذلك النشر. عند أول تشغيل، أدخل بيانات النشر، ثبّت شهادة MITM من إعدادات Android، ثم اضغط **اتصال** ووافق على طلب VPN. يختار التطبيق `google_ip` تلقائيًا عند الاتصال ويستخدم `front_domain` وحده كـ SNI.

وضع MITM يفك تشفير حركة HTTPS على الجهاز باستخدام شهادة المستخدم المثبتة قبل إعادة تمريرها؛ استخدم التطبيق فقط مع إعدادات وخدمات تثق بها.

## المصدر والترخيص

يتضمن هذا المستودع ملفات Android وملفات crate Rust اللازمة لبناء مكتبات Android الأصلية من المشروع الأصلي، مع ملف ترخيص MIT وإشارة إلى المصدر. محتوى المشروع الأصلي مرخص بموجب MIT؛ راجع [`LICENSE`](LICENSE). تمت إضافة هوية RelayGuard وترجمة العربية وتعديلات الواجهة والإعدادات وأيقونة التطبيق لهذا المستودع.
