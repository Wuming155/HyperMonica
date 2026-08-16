package io.github.howard20181.hyperpasskey;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.credentials.CredentialManager;
import android.os.Build;
import android.credentials.selection.IntentCreationResult;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.service.credentials.CallingAppInfo;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.exceptions.NoResultException;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import io.github.libxposed.api.XposedModule;

@SuppressLint({"PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi"})
public class PasskeyHook extends XposedModule {
    private static final String TAG = "HyperPasskey";
    private static final String settingsPackageName = "com.android.settings";
    private static final String securityCenterPackageName = "com.miui.securitycenter";
    private static final String monicaPackageName = "takagi.ru.monica";
    private static final String monicaCredentialProvider =
            "takagi.ru.monica/takagi.ru.monica.passkey.MonicaCredentialProviderService";
    private static final String monicaAutofillService =
            "takagi.ru.monica/takagi.ru.monica.autofill_ng.MonicaAutofillServiceNg";
    private static Field fIsInternationalBuildBoolean;
    private final static Hooker isInternationalBuildHooker = new IsInternationalBuildHooker();

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        System.loadLibrary("dexkit");
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        var classLoader = param.getClassLoader();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                try {
                    hookIntentFactory(classLoader);
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "hook IntentFactory failed", e);
                }
            }
            try {
                hookRequestSession(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "hook RequestSession failed", e);
            }
            try {
                hookCredentialManagerService(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "hook CredentialManagerService failed", e);
            }
        } catch (Throwable tr) {
            log(Log.ERROR, TAG, "Error hooking system service", tr);
        }
    }

    /**
     * Hook CredentialManagerService 构造器,开机时即校正 Monica 为首选,
     * 无需等待用户首次发起凭据请求。
     */
    private void hookCredentialManagerService(ClassLoader classLoader) throws ClassNotFoundException {
        var iClass = classLoader.loadClass("com.android.server.credentials.CredentialManagerService");
        for (Constructor<?> constructor : iClass.getDeclaredConstructors()) {
            hook(constructor).intercept(chain -> {
                chain.proceed();
                var args = chain.getArgs();
                if (!args.isEmpty() && args.get(0) instanceof Context context) {
                    ensureMonicaPreferred(context);
                }
                return null;
            });
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        var classLoader = param.getClassLoader();
        var pn = param.getPackageName();
        try {
            var buildClass = classLoader.loadClass("miui.os.Build");
            fIsInternationalBuildBoolean = buildClass.getDeclaredField("IS_INTERNATIONAL_BUILD");
            fIsInternationalBuildBoolean.setAccessible(true);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "find IS_INTERNATIONAL_BUILD failed", e);
        }
        try (var bridge = DexKitBridge.create(classLoader, true)) {
            switch (pn) {
                case settingsPackageName -> {
                    try {
                        hookDefaultCombinedPicker(classLoader);
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "hook DefaultCombinedPicker failed", e);
                    }
                    try {
                        hookDefaultCombinedPreferenceController(classLoader);
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "hook DefaultCombinedPreferenceController failed", e);
                    }
                    try {
                        hookOnCombiPreferenceClickListener(classLoader, bridge);
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "hook OnCombiPreferenceClickListener failed", e);
                    }
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        try {
                            hookDefaultAppPreferenceController(classLoader);
                        } catch (Exception e) {
                            log(Log.ERROR, TAG, "hook DefaultAppPreferenceController failed", e);
                        }
                    }
                }
                case securityCenterPackageName -> {
                    try {
                        securityCenterApplicationHook(classLoader, bridge);
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "hook SecurityCenterApplication failed", e);
                    }
                }
            }
        }
    }

    private void hookDefaultCombinedPreferenceController(ClassLoader classLoader) throws ClassNotFoundException {
        var iClass = classLoader.loadClass("com.android.settings.applications.credentials.DefaultCombinedPreferenceController");
        if (iClass != null) {
            try {
                var aMethod = iClass.getDeclaredMethod("getCombinedProviderInfos", CredentialManager.class, int.class);
                hook(aMethod).intercept(isInternationalBuildHooker);
            } catch (NoSuchMethodException ignored) {
            }
        }
    }

    private void hookDefaultAppPreferenceController(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var iClass = classLoader.loadClass("com.android.settings.applications.defaultapps.DefaultAppPreferenceController");
        var preferenceClass = classLoader.loadClass("androidx.preference.Preference");
        var aMethod = iClass.getDeclaredMethod("updateState", preferenceClass);
        hook(aMethod).intercept(isInternationalBuildHooker);
    }

    private void hookDefaultCombinedPicker(ClassLoader classLoader) throws ClassNotFoundException {
        var iClass = classLoader.loadClass("com.android.settings.applications.credentials.DefaultCombinedPicker");
        if (iClass != null) {
            try {
                var aMethod = iClass.getDeclaredMethod("setDefaultKey", String.class);
                hook(aMethod).intercept(isInternationalBuildHooker);
            } catch (NoSuchMethodException ignored) {
            }
        }
    }

    private void hookOnCombiPreferenceClickListener(ClassLoader classLoader, DexKitBridge bridge) {
        var onLeftSideClickedMatcher = MethodMatcher.create()
                .name("onLeftSideClicked")
                .paramCount(0)
                .addInvoke("Lcom/android/settings/applications/credentials/CombinedProviderInfo;->launchSettingsActivityIntent(Landroid/content/Context;Ljava/lang/CharSequence;Ljava/lang/CharSequence;I)V");
        bridge.findClass(FindClass.create()
                .searchPackages("com.android.settings.applications.credentials")
                .matcher(ClassMatcher.create().methods(MethodsMatcher.create().add(onLeftSideClickedMatcher)))
        ).findMethod(FindMethod.create().matcher(onLeftSideClickedMatcher)
        ).forEach(methodData -> {
            try {
                hook(methodData.getMethodInstance(classLoader)).intercept(isInternationalBuildHooker);
            } catch (NoSuchMethodException e) {
                log(Log.ERROR, TAG, "hook onLeftSideClicked failed", e);
            }
        });
    }

    private void hookRequestSession(ClassLoader classLoader) throws NoSuchMethodException, ClassNotFoundException, NoSuchFieldException {
        var cRequestSession = classLoader.loadClass("com.android.server.credentials.RequestSession");
        var fHybridService = cRequestSession.getDeclaredField("mHybridService");
        fHybridService.setAccessible(true);
        var aClass = classLoader.loadClass("com.android.server.credentials.RequestSession$SessionLifetime");
        Constructor<?> constructorRequestSession;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            constructorRequestSession = cRequestSession.getDeclaredConstructor(Context.class, aClass,
                    Object.class, int.class, int.class, Object.class, Object.class, String.class,
                    CallingAppInfo.class, Set.class, CancellationSignal.class, long.class, boolean.class);
        } else {
            constructorRequestSession = cRequestSession.getDeclaredConstructor(Context.class, aClass,
                    Object.class, int.class, int.class, Object.class, Object.class, String.class,
                    CallingAppInfo.class, Set.class, CancellationSignal.class, long.class);
        }
        hook(constructorRequestSession).intercept(chain -> {
            chain.proceed();
            fHybridService.set(chain.getThisObject(), "com.google.android.gms/.auth.api.credentials.credman.service.RemoteService");
            if (chain.getArgs().get(0) instanceof Context context) {
                ensureMonicaPreferred(context);
            }
            return null;
        });
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private void hookIntentFactory(ClassLoader classLoader) throws NoSuchMethodException, ClassNotFoundException {
        Method mGetOemOverrideComponentName;
        var classIntentFactory = classLoader.loadClass("android.credentials.selection.IntentFactory");
        var classIntentCreationResultBuilder = classLoader.loadClass("android.credentials.selection.IntentCreationResult$Builder");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            mGetOemOverrideComponentName = classIntentFactory.getDeclaredMethod("getOemOverrideComponentName",
                    Context.class, classIntentCreationResultBuilder, int.class);
        } else {
            mGetOemOverrideComponentName = classIntentFactory.getDeclaredMethod("getOemOverrideComponentName",
                    Context.class, classIntentCreationResultBuilder);
        }
        hook(mGetOemOverrideComponentName).intercept(chain -> {
            var args = chain.getArgs();
            if (args.size() >= 2 && args.get(0) instanceof Context context && args.get(1) instanceof IntentCreationResult.Builder intentResultBuilder) {
                ensureMonicaPreferred(context);
                final String oemComponentString = "com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity";
                try {
                    var oemComponentName = ComponentName.unflattenFromString(oemComponentString);
                    if (oemComponentName != null) {
                        try {
                            var info = context.getPackageManager().getActivityInfo(oemComponentName,
                                    PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY));
                            boolean oemComponentEnabled = info.enabled;
                            int runtimeComponentEnabledState = context.getPackageManager()
                                    .getComponentEnabledSetting(oemComponentName);
                            if (runtimeComponentEnabledState
                                    == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                                oemComponentEnabled = true;
                            } else if (runtimeComponentEnabledState
                                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                                oemComponentEnabled = false;
                            }
                            if (oemComponentEnabled && info.exported) {
                                intentResultBuilder.setOemUiPackageName(oemComponentName.getPackageName());
                                intentResultBuilder.setOemUiUsageStatus(IntentCreationResult
                                        .OemUiUsageStatus.SUCCESS);
                                return oemComponentName;
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            log(Log.ERROR, TAG, "Unable to find oem CredMan UI component: "
                                    + oemComponentString + ".", e);
                        }
                    }
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "Failed to parse OEM component name "
                            + oemComponentString + ": " + e);
                }
            }
            return chain.proceed();
        });
    }


    private void securityCenterApplicationHook(ClassLoader classLoader, DexKitBridge bridge) {
        var cApplication = bridge.getClassData("Lcom/miui/securitycenter/Application;");
        if (cApplication != null) {
            try {
                var mSetStringResourceConfigIfNeed = cApplication.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .paramTypes(Context.class, String.class, int.class)
                                .addInvoke("Landroid/content/res/Resources;->getString(I)Ljava/lang/String;")
                                .addInvoke("Landroid/provider/Settings$Secure;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z")
                        )).single();
                var setStringResourceConfigIfNeedMethodInstance = mSetStringResourceConfigIfNeed.getMethodInstance(classLoader);
                deoptimize(setStringResourceConfigIfNeedMethodInstance);
                var mConfigForAutofillService = cApplication.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .paramTypes(Context.class)
                                .addEqString("autofill_service")
                                .addInvoke(mSetStringResourceConfigIfNeed.getDescriptor())
                        )).single().getMethodInstance(classLoader);
                hook(mConfigForAutofillService).intercept(chain -> null);
            } catch (NoSuchMethodException | NoResultException e) {
                log(Log.WARN, TAG, "hook configForAutofillService", e);
            }
            try {
                var mSetStringArrayResourceConfigIfNeed = cApplication.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .paramTypes(Context.class, String.class, int.class)
                                .addInvoke("Landroid/content/res/Resources;->getStringArray(I)[Ljava/lang/String;")
                                .addInvoke("Landroid/provider/Settings$Secure;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z")
                        )).single();
                var setStringArrayResourceConfigIfNeedMethodInstance = mSetStringArrayResourceConfigIfNeed.getMethodInstance(classLoader);
                deoptimize(setStringArrayResourceConfigIfNeedMethodInstance);
                var mSetDefaultConfigForAutofillAndCredentialManager = cApplication.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .paramTypes(Context.class)
                                .usingEqStrings("credential_service", "credential_service_primary")
                                .addInvoke(mSetStringArrayResourceConfigIfNeed.getDescriptor())
                        )).single().getMethodInstance(classLoader);
                hook(mSetDefaultConfigForAutofillAndCredentialManager).intercept(chain -> null);
            } catch (NoSuchMethodException | NoResultException e) {
                log(Log.ERROR, TAG, "hook setDefaultConfigForAutofillAndCredentialManager", e);
            }
        }
    }

    /**
     * 确保 Monica 为首选凭据提供者与自动填充服务(幂等)。
     * 在 system_server 进程中调用,每次凭据请求/选择器生成时校正,
     * 作为手机管家重置行为的兜底;Monica 未安装时不做任何干预。
     */
    private static void ensureMonicaPreferred(@NonNull Context context) {
        try {
            context.getPackageManager().getPackageInfo(monicaPackageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        try {
            var cr = context.getContentResolver();
            // 凭据提供者列表:确保 Monica 在列,保留其他已启用项
            var list = Settings.Secure.getString(cr, "credential_service");
            if (list == null || list.isBlank()) {
                Settings.Secure.putString(cr, "credential_service", monicaCredentialProvider);
            } else if (!containsComponent(list, monicaCredentialProvider)) {
                var sep = list.contains(";") && !list.contains(",") ? ";" : ",";
                Settings.Secure.putString(cr, "credential_service", monicaCredentialProvider + sep + list);
            }
            // 首选凭据提供者
            var primary = Settings.Secure.getString(cr, "credential_service_primary");
            if (!sameComponent(primary, monicaCredentialProvider)) {
                Settings.Secure.putString(cr, "credential_service_primary", monicaCredentialProvider);
            }
            // 自动填充服务
            var autofill = Settings.Secure.getString(cr, "autofill_service");
            if (!sameComponent(autofill, monicaAutofillService)) {
                Settings.Secure.putString(cr, "autofill_service", monicaAutofillService);
            }
        } catch (Exception e) {
            Log.w(TAG, "ensure Monica preferred failed", e);
        }
    }

    private static boolean sameComponent(@Nullable String a, @Nullable String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        var ca = ComponentName.unflattenFromString(a.trim());
        var cb = ComponentName.unflattenFromString(b.trim());
        return ca != null && ca.equals(cb);
    }

    private static boolean containsComponent(@NonNull String list, @NonNull String target) {
        for (var item : list.split("[,;]")) {
            if (sameComponent(item, target)) return true;
        }
        return false;
    }

    private static class IsInternationalBuildHooker implements Hooker {
        private static final ReentrantLock INTL_LOCK = new ReentrantLock(true); // fair optional
        private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
        private static final ThreadLocal<Boolean> PREV_VALUE = new ThreadLocal<>();

        @Nullable
        @Override
        public Object intercept(@NonNull Chain chain) throws Throwable {
            if (fIsInternationalBuildBoolean == null) return chain.proceed();
            INTL_LOCK.lock();

            try {
                Integer depthObj = DEPTH.get();
                int depth = depthObj != null ? depthObj : 0;
                if (depth == 0) {
                    boolean prev = fIsInternationalBuildBoolean.getBoolean(null);
                    PREV_VALUE.set(prev);
                    if (!prev) {
                        fIsInternationalBuildBoolean.setBoolean(null, true);
                    }
                }
                DEPTH.set(depth + 1);

                try {
                    return chain.proceed();
                } finally {
                    Integer dObj = DEPTH.get();
                    int d = (dObj != null ? dObj : 0) - 1;
                    if (d == 0) {
                        Boolean prev = PREV_VALUE.get();
                        PREV_VALUE.remove();
                        DEPTH.remove();
                        if (prev != null) {
                            fIsInternationalBuildBoolean.setBoolean(null, prev);
                        }
                    } else {
                        DEPTH.set(d);
                    }
                }
            } finally {
                INTL_LOCK.unlock();
            }

        }
    }
}
