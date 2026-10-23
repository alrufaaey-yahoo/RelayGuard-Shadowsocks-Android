# GCP SHADOW for Android

تطبيق Android مخصص يجمع واجهة Shadowsocks مع محرك RelayGuard المضمّن. معرّف الحزمة هو `com.alrufaaey.shadow`، وتبقى إدارة ملف Shadowsocks وخدمة VPN مبنية على مشروع Shadowsocks Android.

## هوية الحزمة في Android

`applicationId = com.alrufaaey.shadow` هو المعرّف الذي يظهر في النظام ويُستخدم للتثبيت و`adb`، أما `namespace = com.github.shadowsocks` فيبقى عمدًا لتطابق أسماء حزم Kotlin/Java وموارد `R` والفئات الموجودة في manifests. لا تغيّر `namespace` أو أسماء `package` في الشيفرة لمجرد تغيير هوية التطبيق؛ تغييرها يتطلب إعادة تسمية واسعة لكل الوحدات. تطبيق Android TV منفصل ويحتفظ بالمعرّف `com.github.shadowsocks.tv`. أزيل إعداد Google Services القديم من وحدة الهاتف لأنها لا تستخدم Firebase، ويقتصر ملف Firebase المتبقي على معرّف TV.

## مسار الاتصال

عند تفعيل **Settings → Script relay → Enable Script relay chain** ثم الضغط على اتصال، تبدأ خدمة RelayGuard أولًا على منفذ HTTP عشوائي مربوط إلى `127.0.0.1` فقط. بعد نجاح فحص جاهزية المستمع يبدأ `ss-local` ويستخدم ذلك المنفذ كـHTTP CONNECT upstream للوصول إلى خادم Shadowsocks. يوجّه VPN التطبيقات إلى SOCKS المحلي الخاص بـShadowsocks:

```text
تطبيقات الجهاز → VPN/TUN → SOCKS المحلي لـShadowsocks
             → اتصال Shadowsocks مشفّر إلى VPS عبر HTTP CONNECT
             → خادم Shadowsocks/VPS → الإنترنت
```

لا يوجَّه TUN مباشرة إلى SOCKS الخاص بـRelayGuard، ولا يوجد upstream عكسي إلى Shadowsocks داخل RelayGuard؛ هذا يمنع الحلقة ويجعل عنوان الخروج بعد اتصال Shadowsocks هو عنوان VPS. لا يتم الاحتفاظ بمنفذ ثابت، ويُوقف RelayGuard بعد إغلاق عميل Shadowsocks. إذا تعذر إنشاء الوكيل المحلي، يفشل بدء الاتصال بدل الرجوع بصمت إلى اتصال مباشر.

هذا النقل TCP فقط: يُعطّل UDP وUDP fallback أثناء السلسلة. كما أن إضافات Shadowsocks غير مدعومة مع هذا النقل حاليًا. يلزم أن يستطيع RelayGuard فتح اتصال TCP إلى مضيف VPS ومنفذه؛ أما استخدام Apps Script لتمرير أنواع أخرى من حركة HTTP/HTTPS فيخضع لقدرات إعداد RelayGuard والنشر نفسه. تثبيت شهادة CA اختياري بحسب استخدام اعتراض TLS، ولا تثبّت إلا شهادة تثق بها.

## الإعداد

1. ثبّت التطبيق وافتح **Settings → Script relay**.
2. أدخل رابط HTTPS لملف نصي مباشر ثم اضغط **Refresh RelayGuard settings**. يجب أن يحتوي الملف على:

   ```text
   URL=https://script.google.com/macros/s/DEPLOYMENT_ID/exec
   KEY=replace-with-your-auth-key
   FRONT=google.com
   ```

   يقبل `URL` رابط Apps Script أو Deployment ID. يُنزّل التطبيق ملفًا لا يتجاوز 32 KiB، ويتطلب HTTPS ورمز HTTP 200، ويتحقق من القيم قبل حفظها. لا تضع المفتاح في مستودع عام أو رابط يمكن لأي شخص تعديله. يظل المفتاح محفوظًا في قاعدة بيانات التطبيق الخاصة ولا يظهر في ملخص واجهة الإعدادات.
3. اضبط وضع الخدمة في Shadowsocks على **VPN**. عند تفعيل Script relay، امنع التطبيق تغيير الوضع أثناء اتصال نشط.
4. فعّل **Enable Script relay chain** ثم اتصل بملف Shadowsocks المستورد. يبدأ RelayGuard تلقائيًا قبل `ss-local`، ولا يحتاج المستخدم إلى كتابة المنفذ المحلي.
5. خيارات البروتوكول المتقدمة وتصدير شهادة RelayGuard متاحة في صفحة Script relay. قد تحتاج بعض التطبيقات إلى شهادة CA، وقد ترفض التطبيقات ذات certificate pinning الشهادة.

## ملفات الخوادم والحافظة

لا ينشئ التطبيق ملف `example.shadowsocks.org` عند التثبيت. استيراد ملفات الخادم المتاح من الواجهة هو **Import from Clipboard**. يقبل التطبيق روابط `alrufaaey://...`، كما يقبل روابط `ss://...` القديمة للتوافق عند الاستيراد.

في القائمة يظهر الاسم الذي تختاره فقط؛ لا يعرض عنوان الخادم أو المنفذ، وأزرار التعديل والمشاركة والبحث مخفية. عند التصدير إلى الحافظة يطلب التطبيق اسمًا للملف، ثم يتيح:

- **Copy without password**: ينسخ رابطًا صالحًا بصيغة `alrufaaey://...` ويضم إعداد الاتصال اللازم للاستيراد.
- **Protect with password**: يشفّر الرابط بالكامل إلى صيغة `alrufaaey://protected/v1/...` باستخدام AES-256-GCM ومفتاح مشتق عبر PBKDF2-HMAC-SHA256 (200,000 دورة). يتطلب كلمة مرور من 8 أحرف على الأقل؛ يلزم إدخالها عند الاستيراد من الحافظة. لا يُرسل التطبيق كلمة المرور إلى أي خادم.

إبقاء النص العادي في الحافظة يظل غير محمي؛ اختر التشفير عند مشاركة بيانات حساسة. استيراد/تصدير الملفات، وخيارات البحث والمسح/الإضافة اليدوية مخفية من واجهة المستخدم.

## البناء والتثبيت

المشروع يضم مصادر Shadowsocks Android ومصادر محرك RelayGuard. تُبنى مكتبات RelayGuard وShadowsocks native ضمن Gradle.

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo install cargo-ndk --locked
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
./gradlew :mobile:assembleDebug -PTARGET_ABI=arm64 -PCARGO_PROFILE=release
```

نسخة Debug موقعة بمفتاح debug الافتراضي ومخصصة للاختبار. معرّف الحزمة مختلف عن التطبيق الرسمي، لذلك يُثبّت هذا التطبيق كحزمة مستقلة؛ لا تُنقل إعدادات أو ملفات التطبيق الرسمي تلقائيًا.

## المصدر والتراخيص

- واجهة Shadowsocks Android ومصادرها مبنية على [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)، مرخصة GPL-3.0-or-later؛ راجع [`LICENSE`](LICENSE).
- محرك RelayGuard من [alrufaaey-yahoo/RelayGuard-Android](https://github.com/alrufaaey-yahoo/RelayGuard-Android)، وترخيصه MIT محفوظ في [`relayguard/LICENSE`](relayguard/LICENSE).
- الإشعارات والاعتمادات التابعة موضحة في [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

هذا fork مستقل للاختبار والتطوير وليس إصدارًا رسميًا من Shadowsocks أو RelayGuard. راجع سياسات خدمات النشر والشهادات والقوانين المحلية قبل الاستخدام.
