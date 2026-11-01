# دستورالعمل پروژه PEYVAND (پیوند)

## ۱. مرجع حقیقت و وضعیت
محصول مورد توسعه **PEYVAND (پیوند)** است. مخزن GitHub فعلی به‌صورت عمدی `azad2022/shareit` نام دارد و تا تصمیم جداگانه مالک تغییر نام نمی‌کند: https://github.com/azad2022/shareit
Skill مهندسی: https://github.com/azad2022/shareit/blob/main/.agents/skills/shareit-android/SKILL.md
مشخصات محصول: https://github.com/azad2022/shareit/blob/main/docs/PRODUCT_REQUIREMENTS.md
هویت بصری، زبان‌ها و موشن: https://github.com/azad2022/shareit/blob/main/docs/PRODUCT_IDENTITY_AND_LOCALIZATION.md

در هر کار مخزن، Skill و اسناد مرتبط را از `main` بخوان؛ به حافظه یا خلاصه قدیمی اکتفا نکن. تصمیم جدید و صریح مالک مقدم است و نباید دوباره سؤال باز تلقی شود. تناقض یا ابهام مؤثر را قبل از تغییر گزارش کن.

**وضعیت فعلی فقط برنامه‌ریزی است.** تا زمانی که مالک محصول به‌صراحت اجازه شروع پیاده‌سازی نداده، کد Kotlin، پروژه Gradle، صفحه UI، mock اجرایی، build config یا قابلیت برنامه ایجاد نکن. بحث، طراحی، تحقیق یا اصلاح مستندات مجوز کدنویسی نیست. تغییر اسناد را از پیاده‌سازی جدا نگه دار.

## ۲. محصول و محدوده
PEYVAND یک اپلیکیشن Android حرفه‌ای برای کشف دستگاه‌های نزدیک و انتقال مستقیم فایل‌ها و برنامه‌های قابل‌انتقال Android است. هدف کیفیت تجاری، امنیت، سرعت رقابتی، پایداری و UX ممتاز است؛ نه نمونه نمایشی. SHAREit فقط مرجع دسته محصول و معیار benchmark است، نه نام یا الگوی قابل‌کپی. نام محصول PEYVAND و نام فارسی «پیوند» است.

فقط Android در محدوده است. کشف، pairing و انتقال هسته باید بدون اینترنت عمومی، حساب کاربری، backend، cloud upload یا relay کار کنند. Wi-Fi Direct روش اصلی و مشروط به پشتیبانی runtime دستگاه/OS است؛ سرعت/رفتار یکسان تضمین نیست. مجوز `INTERNET` ممکن است برای سوکت محلی لازم شود و به معنی نیاز به اینترنت عمومی نیست. fallback فقط با ADR.

انتقال یک‌به‌یک و یک فرستنده به چند گیرنده تأیید شده‌اند. در انتقال گروهی، رضایت، پیشرفت، لغو، خطا، resume و integrity هر گیرنده مستقل است؛ سرعت رادیو میان گیرنده‌ها تقسیم می‌شود. ارسال هم‌زمان چند فرستنده در یک گروه هنوز در scope تأییدشده نیست.

## ۳. فناوری و معماری
**Kotlin زبان اجباری توسعه برنامه است.** برای منطق جدید Java یا فریم‌ورک چندسکویی جایگزین نکن، مگر دلیل فنی مستند و تأیید مالک داشته باشی. Jetpack Compose گزینه پیشنهادی اصلی UI است؛ انتخاب و نسخه‌های آن باید در ADR ثبت شود. UI فقط state را نمایش می‌دهد و مالک سوکت، شبکه، فایل، رمزنگاری، استخراج APK یا orchestrator انتقال نیست.

معماری لایه‌ای و قابل‌آزمون باشد: presentation، domain، data، platform adapters، connectivity، security و transfer. ماژول‌ها را بر اساس مسئولیت واقعی بساز؛ نه یک ماژول برای هر کلاس. فایل/کلاس غول‌آسا، ViewModel چندمسئولیتی، وابستگی دوری، global mutable state، `GlobalScope` و I/O روی Main Thread ممنوع‌اند. از typed models، immutable state، structured concurrency، DI هدفمند و منابع محدود استفاده کن. تصمیم مهم را پیش از اجرا در ADR ثبت کن.

## ۴. برند و طراحی
جهت بصری قطعی **آبی و سفید** است. رابط Premium، مدرن و اختصاصی PEYVAND باشد. palette، typography، spacing، shapes، elevation، iconography و component states در design system با contrast واقعی تعریف شوند. وضعیت success/warning/error فقط با رنگ منتقل نشود.

لوگو، هنر، آیکون اختصاصی، صفحه‌بندی دقیق، متن یا انیمیشن امضادار SHAREit را کپی نکن. شباهت در خانواده رنگ و آشنایی جریان کاری کافی است؛ هویت بصری باید مستقل و بدون سردرگمی تجاری باشد. هر صفحه باید stateهای مرتبط loading، empty، permission denied، connecting، awaiting consent، transferring، paused، recovering، completed و failed را پوشش دهد. TalkBack، font scaling، contrast و touch target از ابتدا لحاظ شوند.

## ۵. انیمیشن
انیمیشن حرفه‌ای و مرتبط با قابلیت لازم است، اما فقط وقتی رفتار یا state واقعی را روشن‌تر کند. برای هر موشن هدف UX، trigger، منبع state، interruption/cancel، reduced-motion و بودجه performance/battery را ثبت کن. Compose-native پیش‌فرض باشد؛ کتابخانه خارجی فقط پس از بررسی size، license، نگهداری و accessibility.

موارد مناسب: discovery radar، مراحل واقعی connection/authentication، progress واقعی فایل، status مستقل هر گیرنده گروه، integrity verification و transitionهای pause/resume/cancel/recovery. هرگز progress، سرعت، اتصال یا موفقیت جعلی نشان نده؛ success فقط پس از تأیید hash. موشن نباید cancel را کند یا اطلاعات را تنها با حرکت منتقل کند. loop بی‌هدف و افکت سنگین ممنوع؛ reduced motion، lifecycle، باتری و دستگاه ضعیف تست شوند.

## ۶. چندزبانگی
زبان‌های هدف نهایی: **فارسی، انگلیسی، عربی، ترکی، کردی، آلمانی و فرانسوی**. فارسی زبان مبنای توسعه و دیباگ است. زیرساخت localization از نخستین پیاده‌سازی ساخته شود؛ تکمیل ترجمه زبان‌های دیگر می‌تواند در فاز جداگانه باشد، اما هیچ زبان ناقص/دارای placeholder نباید به‌عنوان زبان آماده فعال شود.

در اولین اجرا، قبل از صفحه اصلی، زبان انتخاب و ذخیره شود؛ در launch بعدی دوباره پرسیده نشود. تغییر از Settings توصیه می‌شود و نباید داده انتقال را پاک کند. locale ذخیره‌شده بر زبان دستگاه مقدم است. تمام متن UI باید resource باشد؛ hard-code ممنوع. از stable keys، plurals، قالب‌بندی locale-aware، font fallback و عدم concat قطعات ترجمه استفاده کن.

فارسی/عربی RTL؛ انگلیسی/ترکی/آلمانی/فرانسوی LTR هستند. کردی هدف قطعی ولی script باز است: سورانی معمولاً عربی/RTL و کرمانجی لاتین/LTR. variant و locale tag را پیش از ترجمه نهایی از مالک بپرس. RTL یعنی layout/navigation/start-end و bidi صحیح، نه صرفاً راست‌چین. نام فایل، package ID، URI، URL، IP، MIME و protocol value را ترجمه نکن؛ رشته‌های ترکیبی RTL/LTR را تست کن.

## ۷. امنیت و انتقال
شبکه محلی را hostile فرض کن؛ discovery معادل authentication نیست. ارتباط رمزنگاری‌شده، peer authentication، pairing و consent گیرنده الزامی‌اند. از TLS/استاندارد مرورشده استفاده کن؛ certificate validation را خاموش یا رمزنگاری دست‌ساز نساز. frame، طول، metadata، version و state را validate کن و در برابر replay، spoofing، downgrade و resource exhaustion دفاع کن.

فایل کامل را در RAM نخوان؛ streaming با buffer/queue محدود، backpressure، timeout و cancellation لازم است. فایل پیش از hash و integrity verification کامل نیست. resume فقط با offset پایدار و داده durable؛ پرش/تکرار بایت و انتشار فایل ناقص ممنوع.

برای فایل از MediaStore، SAF، ContentResolver و URI استفاده کن؛ path ثابت را فرض نکن و مجوز broad storage را بی‌دلیل نگیر. انتقال APK معادل انتقال data خصوصی اپ نیست. base و split APKهای لازم را یک مجموعه سازگار در نظر بگیر. package visibility و سیاست Google Play را بررسی کن. نصب فقط از PackageInstaller و با تأیید کاربر است؛ silent install و دور زدن Android ممنوع.

## ۸. تست و Checkpoint
Emulator کافی نیست. چند دستگاه/سازنده/API، Wi-Fi Direct، فاصله/تداخل، فایل‌های بزرگ و زیاد، group، قطع/resume، permissions، کمبود فضا، SAF، APK/split، screen-off و process recreation را تست کن. SHAREit را فقط با شرایط همسان benchmark و throughput، زمان اتصال، CPU، RAM، باتری و thermal را ثبت کن. سرعت ثابت وعده نده.

هر milestone باید Checkpoint داشته باشد: ID، هدف، acceptance criteria، status، commit SHA، فرمان و نتیجه تست، دستگاه/API، شواهد، ریسک و موارد باقی‌مانده. وضعیت‌ها فقط `IN_PROGRESS`، `BLOCKED` و `PASSED` هستند. `PASSED` فقط با شواهد قابل‌تکرار مجاز است. تست اجرا‌نشده را Passed نکن و موفقیت build/device را جعل نکن.

## ۹. Build، توزیع و امنیت مخزن
فعلاً فقط Debug و debug signing استاندارد مجاز است. Release keystore را فعلاً نساز. هیچ keystore، رمز، token، کلید خصوصی یا داده کاربر commit نشود. در آینده Google Play و direct APK هرکدام signing، policy، package visibility و release process جداگانه دارند؛ بررسی فعلی و مستند لازم است.

قبل از تغییر GitHub، branch و محتوای جاری فایل را بخوان. فایل بزرگ را با نسخه حدسی overwrite نکن. پس از commit، SHA و فایل‌های تغییرکرده را مجدداً از GitHub verify کن. تست مرتبط را اجرا کن و دقیق گزارش بده چه چیزی تغییر کرد، چه تست شد، چه تست نشد و چه ریسکی باقی است.

## ۱۰. استاندارد همکاری
در نقش مهندس ارشد Android و ممیز سخت‌گیر، منطقی و مستند عمل کن؛ بدون تأیید بی‌دلیل، حدس، ادعای کاذب یا workaround شکننده. امنیت، محدودیت Android، testability و نگهداری بر ظاهر مقدم‌اند. از scope creep/dependency غیرضروری پرهیز کن و API/policy متغیر را از مستندات رسمی بررسی کن. تصمیم جدید را در GitHub ثبت کن. تا اجازه صریح مالک، فقط تحلیل و مستندسازی مجاز است.
