# RelayGuard × Shadowsocks for Android

نسخة Android مبنية على واجهة **Shadowsocks** الأصلية، مع دمج محرك **RelayGuard** بوصفه طبقة محلية أمام عميل Shadowsocks. يبقى التطبيق وإدارة ملفات الاتصال وخدمة VPN مبنية على Shadowsocks.

## مسار الاتصال

عند تفعيل **Settings → Script relay → Enable Script relay chain** واستخدام وضع الخدمة **VPN**، يكون مسار TCP:

```text
تطبيقات الجهاز → TUN / tun2socks → RelayGuard SOCKS5 (127.0.0.1:18081)
               → Shadowsocks local SOCKS (127.0.0.1:<portProxy>)
               → خادم Shadowsocks/VPS → الوجهة
```

في وضع Apps Script، تمر اتصالات HTTP/HTTPS التي يعالجها RelayGuard عبر نشر Apps Script؛ اتصال RelayGuard الخارجي إلى Google front endpoint يمر كذلك من SOCKS الخاص بـShadowsocks. الاتصالات الخام التي لا تُرسل عبر Apps Script تمر إلى الوجهة من خلال Shadowsocks. لا يسمح هذا الدمج برجوع تدفق محدد مباشرةً إلى الشبكة عند تعطل وصلة upstream SOCKS؛ يفشل ذلك التدفق بدل تجاوز سلسلة الوكلاء.

**مهم:** سلسلة relay مخصصة لوضع VPN في Shadowsocks. عند إيقافها، يعود TUN إلى SOCKS الأصلي الخاص بـShadowsocks. تطبيقات HTTPS التي لا تثق بشهادات المستخدم أو تستخدم certificate pinning قد لا تعمل مع وضع MITM؛ بعض التطبيقات لا تقبل شهادات CA التي يضيفها المستخدم أصلًا.

## الإعداد على الهاتف

1. أضف ملف Shadowsocks صالحًا يحتوي خادم VPS، ثم اترك **Settings → Service mode** على **VPN**.
2. افتح **Settings → Script relay**. أدخل رابط نشر Apps Script الكامل المنتهي بـ`/exec` أو Deployment ID، و`auth_key` المطابق للنشر. يمكن إدخال عدة روابط/معرّفات مفصولة بمسافة أو فاصلة أو فاصلة منقوطة.
3. اضبط `front_domain` عند الحاجة. الافتراضي `www.google.com`.
4. اضغط **Install RelayGuard CA**. على Android 11 وما بعده يصدّر التطبيق الشهادة إلى `Downloads/relayguard-ca.crt` ويفتح إعدادات النظام؛ ابحث عن “CA certificate” وثبّتها لفئة “VPN and apps”. لا تثبّت شهادة إلا إذا كنت تثق بنشر Apps Script الذي أدخلته.
5. راجع خيارات **Advanced**، ثم فعّل **Enable Script relay chain**. اتصل من شاشة Shadowsocks ووافق على إذن VPN. يستخدم التطبيق ملف Shadowsocks الحالي كوصلة upstream تلقائيًا.

يبقى `auth_key` في قاعدة بيانات التطبيق الخاصة ولا يظهر في ملخص الحقل. بيانات اعتماد نشر Apps Script حساسة؛ لا تشارك لقطات الشاشة أو ملفات النسخ الاحتياطي التي قد تتضمنها.

## خيارات Advanced المدمجة

تتضمن صفحة الإعدادات: التحقق من شهادة TLS، YouTube عبر Apps Script، مستوى السجل، عدد الطلبات المتوازية، حظر QUIC وSTUN وDoH، تمرير DoH عبر الترحيل، فرض HTTP/1.1، وإعدادات تجميع الدفعات. تم تثبيت `upstream_socks5` داخليًا على منفذ Shadowsocks المحلي كي لا ينفصل ترتيب السلسلة أو يسمح بتجاوزها.

## البناء

المشروع يضم مصادر Shadowsocks Android ومصادر crate Rust الخاص بـRelayGuard. إعداد RelayGuard Rust مضاف إلى مهمة `mobile:cargoBuildRelayGuard` ويُستدعى قبل تجميع APK.

المتطلبات: JDK 17 أو أحدث، Android SDK Platform 36، Build Tools 36.0.0، Android NDK 27.2.12479018 أو إصدار متوافق، Rust stable، أهداف Android الأربعة، و`cargo-ndk`.

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo install cargo-ndk --locked
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
./gradlew assembleDebug
```

للتجميع لمعمارية واحدة أثناء التطوير (على سبيل المثال أجهزة ARM64):

```bash
./gradlew assembleDebug -PTARGET_ABI=arm64
```

المفتاح الافتراضي الخاص بـGradle يوقّع نسخة Debug لأغراض الاختبار فقط. لا يتضمن المستودع مفتاح Release خاصًا؛ جهّز مفتاحك خارج المستودع قبل توزيع إصدار Release.

## مصدر كل مشروع والترخيص

- واجهة Shadowsocks Android ومصادرها مبنية على [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)، مرخصة GPL-3.0-or-later؛ راجع [`LICENSE`](LICENSE).
- محرك RelayGuard من [alrufaaey-yahoo/RelayGuard-Android](https://github.com/alrufaaey-yahoo/RelayGuard-Android)، وترخيصه MIT محفوظ في [`relayguard/LICENSE`](relayguard/LICENSE).
- إشعارات واعتمادات المصادر التابعة في [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

هذا fork مستقل للاختبار والتطوير وليس إصدارًا رسميًا من Shadowsocks أو RelayGuard. استخدام Apps Script وMITM يخضع لسياسات Google والقوانين المحلية؛ تحقق من إعداد النشر والشهادة قبل الاستخدام.
