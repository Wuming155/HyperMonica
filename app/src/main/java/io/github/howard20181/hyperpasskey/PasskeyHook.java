package io.github.howard20181.hyperpasskey;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.credentials.CredentialManager;
import android.os.Build;
import android.credentials.selection.IntentCreationResult;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.service.credentials.CallingAppInfo;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.luckypray.dexkit.DexKitCacheBridge;
import org.luckypray.dexkit.exceptions.NoResultException;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import io.github.libxposed.api.XposedModule;

@SuppressLint({"PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi"})
public class PasskeyHook extends XposedModule {
    private static final String TAG = "HyperMonica";
    private static final String settingsPackageName = "com.android.settings";
    private static final String securityCenterPackageName = "com.miui.securitycenter";
    private static final String PREFS_GROUP = ConfigActivity.PREFS_GROUP;
    private static final String PREF_KEY_PREFERRED_PACKAGE =
            ConfigActivity.PREF_KEY_PREFERRED_PACKAGE;
    private static final String CREDENTIAL_SERVICE_ACTION =
            "android.service.credentials.CredentialProviderService";
    private static final String AUTOFILL_SERVICE_ACTION =
            "android.service.autofill.AutofillService";
    private static volatile SharedPreferences prefs;
    private static volatile String cachedPreferredPkg;
    private static volatile ComponentName cachedPreferredCredentialService;
    private Object[] param;
    private static final int PARAM_PACKAGE_NAME = 0;
    private static final int PARAM_CLASS_LOADER = 1;
    private static final int PARAM_VERSION_NAME = 2;
    private static final int PARAM_VERSION_CODE = 3;
    private final Set<String> hookedIds = new HashSet<>();
    private DexKitCacheBridge.RecyclableBridge bridge;

    private HookBuilder hookE(Executable executable) {
        var builder = hook(executable);

        if (getApiVersion() >= 102) {
            var id = executable.toGenericString();
            builder.setId(id);
            hookedIds.add(id);
        }

        return builder;
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        UnsafeUtils.INSTANCE.setXposedModule(this);
        System.loadLibrary("dexkit");
        try {
            prefs = getRemotePreferences(PREFS_GROUP);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "getRemotePreferences failed, preferred provider is fixed to none", t);
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        var classLoader = param.getClassLoader();
        this.param = new Object[]{
                "system",
                classLoader,
                "",
                (long) 0
        };
        hookSystemServer(classLoader);
    }

    private void hookSystemServer(ClassLoader classLoader) {
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
     * Hook CredentialManagerService 构造器,开机时即校正首选提供者,
     * 无需等待用户首次发起凭据请求。
     */
    private void hookCredentialManagerService(ClassLoader classLoader) throws ClassNotFoundException {
        var iClass = classLoader.loadClass("com.android.server.credentials.CredentialManagerService");
        for (Constructor<?> constructor : iClass.getDeclaredConstructors()) {
            hookE(constructor).intercept(chain -> {
                chain.proceed();
                var args = chain.getArgs();
                if (!args.isEmpty() && args.get(0) instanceof Context context) {
                    ensurePreferred(context);
                }
                return null;
            });
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        var classLoader = param.getClassLoader();
        var packageName = param.getPackageName();
        Context ctx = ActivityThread.currentActivityThread().getSystemContext();
        var pm = ctx.getPackageManager();
        DexKitCacheBridge.init(MemoryCache.INSTANCE);
        long versionCode = 0;
        String versionName = "";
        try {
            var info = pm.getPackageInfo(packageName, 0);
            versionCode = info.getLongVersionCode();
            versionName = info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        var appTag = packageName + ":" + versionName + "-" + versionCode;
        this.param = new Object[]{
                packageName,
                classLoader,
                versionName,
                versionCode
        };
        try (var bridge = DexKitCacheBridge.create(appTag, classLoader)) {
            hookPackage(packageName, classLoader, bridge);
            this.bridge = bridge;
        }
    }

    private void hookPackage(String packageName, ClassLoader classLoader, DexKitCacheBridge.RecyclableBridge bridge) {
        switch (packageName) {
            case settingsPackageName -> {
                Field fIsInternationalBuildBoolean = null;
                try {
                    var buildClass = classLoader.loadClass("miui.os.Build");
                    fIsInternationalBuildBoolean = buildClass.getDeclaredField("IS_INTERNATIONAL_BUILD");
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "find IS_INTERNATIONAL_BUILD failed", e);
                }
                var isInternationalBuildHooker = new IsInternationalBuildHooker(fIsInternationalBuildBoolean);
                try {
                    hookDefaultCombinedPicker(classLoader, isInternationalBuildHooker);
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "hook DefaultCombinedPicker failed", e);
                }
                try {
                    hookDefaultCombinedPreferenceController(classLoader, isInternationalBuildHooker);
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "hook DefaultCombinedPreferenceController failed", e);
                }
                try {
                    hookOnCombiPreferenceClickListener(classLoader, bridge, isInternationalBuildHooker);
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "hook OnCombiPreferenceClickListener failed", e);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    try {
                        hookCredentialManagerPreferenceController(classLoader, bridge);
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "hook CredentialManagerPreferenceController failed", e);
                    }
                }
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    try {
                        hookDefaultAppPreferenceController(classLoader, isInternationalBuildHooker);
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "hook DefaultAppPreferenceController failed", e);
                    }
                }
                try {
                    hookCredentialProviderInfoStatus(classLoader);
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "hook CredentialProviderInfo status failed", e);
                }
            }
            case securityCenterPackageName -> {
                try {
                    securityCenterHook(classLoader, bridge);
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "hook SecurityCenterApplication failed", e);
                }
            }
        }
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        param.setSavedInstanceState(this.param);
        if (this.bridge != null) {
            this.bridge.destroy();
            this.bridge = null;
        }
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        UnsafeUtils.INSTANCE.setXposedModule(this);
        var isSystemServer = param.isSystemServer();
        if (param.getSavedInstanceState() instanceof Object[] outState
                && outState[PARAM_PACKAGE_NAME] instanceof String packageName
                && outState[PARAM_CLASS_LOADER] instanceof ClassLoader classLoader
                && outState[PARAM_VERSION_NAME] instanceof String versionName
                && outState[PARAM_VERSION_CODE] instanceof Long versionCode) {
            this.param = new Object[]{
                    packageName,
                    classLoader,
                    versionName,
                    versionCode
            };
            try {
                if (isSystemServer) {
                    hookSystemServer(classLoader);
                } else {
                    System.loadLibrary("dexkit");
                    DexKitCacheBridge.init(MemoryCache.INSTANCE);
                    var appTag = packageName + ":" + versionName + "-" + versionCode;
                    try (var bridge = DexKitCacheBridge.create(appTag, classLoader)) {
                        hookPackage(packageName, classLoader, bridge);
                        this.bridge = bridge;
                    }
                }
            } catch (Throwable tr) {
                log(Log.ERROR, TAG, "Hot reload failed", tr);
            }
        }
        param.getOldHookHandles().forEach(h -> {
            if (!hookedIds.contains(h.getId())) {
                h.unhook();
            }
        });
    }

    /**
     * 在设置进程强制所选提供者的凭据状态为"已启用/首选"。
     * HyperOS 设置页对状态的判定不完全跟随 credential_service 配置,
     * 这里拦截 framework CredentialProviderInfo 的状态查询做最终修正;
     * 未选择提供者时不做任何干预。
     */
    private void hookCredentialProviderInfoStatus(ClassLoader classLoader) throws ClassNotFoundException {
        var iClass = classLoader.loadClass("android.credentials.CredentialProviderInfo");
        for (var methodName : new String[]{"isEnabled", "isPrimary"}) {
            try {
                var method = iClass.getDeclaredMethod(methodName);
                hookE(method).intercept(chain -> {
                    var result = chain.proceed();
                    if (!(result instanceof Boolean b) || !b) {
                        try {
                            var cn = (ComponentName) iClass
                                    .getDeclaredMethod("getComponentName")
                                    .invoke(chain.getThisObject());
                            var preferred = getPreferredCredentialService();
                            if (preferred != null && preferred.equals(cn)) {
                                Log.i(TAG, "Force " + methodName + "=true for " + cn
                                        + " (was " + result + ")");
                                return true;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    return result;
                });
                Log.i(TAG, "hooked CredentialProviderInfo." + methodName);
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "CredentialProviderInfo." + methodName + " not found", e);
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private void hookCredentialManagerPreferenceController(ClassLoader classLoader, DexKitCacheBridge.RecyclableBridge bridge) throws ClassNotFoundException, NoSuchMethodException, NoSuchFieldException {
        var combiPreferenceClass = classLoader.loadClass("com.android.settings.applications.credentials.CredentialManagerPreferenceController$CombiPreference");
        var onBindViewHolder = combiPreferenceClass.getDeclaredMethod("onBindViewHolder", classLoader.loadClass("androidx.preference.PreferenceViewHolder"));
        var mChecked = combiPreferenceClass.getDeclaredField("mChecked");
        mChecked.setAccessible(true);
        var mOnClickListener = combiPreferenceClass.getDeclaredField("mOnClickListener");
        mOnClickListener.setAccessible(true);
        var mSwitch = combiPreferenceClass.getDeclaredField("mSwitch");
        mSwitch.setAccessible(true);
        var maybeUpdateContentDescriptionMethod = combiPreferenceClass.getDeclaredMethod("maybeUpdateContentDescription");
        var viewHolder = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView$ViewHolder");
        var itemViewField = viewHolder.getDeclaredField("itemView");
        itemViewField.setAccessible(true);
        var R$idClass = classLoader.loadClass("com.android.settingslib.R$id");
        var switchWidget = R$idClass.getDeclaredField("switchWidget");
        switchWidget.setAccessible(true);
        var onCheckChangedMatcher = MethodMatcher.create()
                .name("onCheckChanged")
                .paramTypes(combiPreferenceClass, boolean.class)
                .anyOf(
                        MethodMatcher.create()
                                .addInvoke("Lcom/android/settings/applications/credentials/CredentialManagerPreferenceController;->togglePackageNameEnabled(Ljava/lang/String;)Z"),
                        MethodMatcher.create()
                                .addInvoke("Lcom/android/settings/applications/credentials/CredentialManagerPreferenceController;->togglePackageNameDisabled(Ljava/lang/String;)V"));
        bridge.getMethods(FindMethod.create()
                .searchPackages("com.android.settings.applications.credentials")
                .matcher(onCheckChangedMatcher)
        ).forEach(methodData -> {
            try {
                var onCheckChangedMethod = methodData.getMethodInstance(classLoader);
                hookE(onBindViewHolder).intercept(chain -> {
                    var result = chain.proceed();
                    var combiPreference = chain.getThisObject();
                    if (mSwitch.get(combiPreference) == null && itemViewField.get(chain.getArg(0)) instanceof View itemView) {
                        var checkableView = itemView.findViewById(switchWidget.getInt(null));
                        if (checkableView instanceof CompoundButton switchView) {
                            switchView.setChecked(mChecked.getBoolean(combiPreference));
                            switchView.setOnClickListener(buttonView -> {
                                try {
                                    var clickListener = mOnClickListener.get(combiPreference);
                                    if (clickListener == null) {
                                        return;
                                    }
                                    var isChecked = switchView.isChecked();
                                    boolean accepted = (boolean) getInvoker(onCheckChangedMethod)
                                            .invoke(clickListener, combiPreference, isChecked);
                                    if (!accepted) {
                                        UnsafeUtils.INSTANCE.setBooleanField(mChecked, combiPreference, false);
                                        switchView.setChecked(false);
                                    }
                                } catch (IllegalAccessException | InvocationTargetException e) {
                                    log(Log.ERROR, TAG, "Failed setOnCheckedChangeListener", e);
                                }
                            });
                            UnsafeUtils.INSTANCE.setObjectField(mSwitch, combiPreference, switchView);
                            getInvoker(maybeUpdateContentDescriptionMethod).invoke(combiPreference);
                        }
                    }
                    return result;
                });
            } catch (NoSuchMethodException e) {
                log(Log.ERROR, TAG, "Failed to find onCheckChanged", e);
            }
        });
    }

    private void hookDefaultCombinedPreferenceController(ClassLoader classLoader, Hooker isInternationalBuildHooker) throws ClassNotFoundException {
        var iClass = classLoader.loadClass("com.android.settings.applications.credentials.DefaultCombinedPreferenceController");
        if (iClass != null) {
            try {
                var aMethod = iClass.getDeclaredMethod("getCombinedProviderInfos", CredentialManager.class, int.class);
                deoptimize(aMethod);
                hookE(aMethod).intercept(isInternationalBuildHooker);
            } catch (NoSuchMethodException ignored) {
            }
        }
    }

    private void hookDefaultAppPreferenceController(ClassLoader classLoader, Hooker isInternationalBuildHooker) throws ClassNotFoundException, NoSuchMethodException {
        var iClass = classLoader.loadClass("com.android.settings.applications.defaultapps.DefaultAppPreferenceController");
        var preferenceClass = classLoader.loadClass("androidx.preference.Preference");
        var aMethod = iClass.getDeclaredMethod("updateState", preferenceClass);
        deoptimize(aMethod);
        hookE(aMethod).intercept(isInternationalBuildHooker);
    }

    private void hookDefaultCombinedPicker(ClassLoader classLoader, Hooker isInternationalBuildHooker) throws ClassNotFoundException {
        var iClass = classLoader.loadClass("com.android.settings.applications.credentials.DefaultCombinedPicker");
        if (iClass != null) {
            try {
                var aMethod = iClass.getDeclaredMethod("setDefaultKey", String.class);
                deoptimize(aMethod);
                hookE(aMethod).intercept(isInternationalBuildHooker);
            } catch (NoSuchMethodException ignored) {
            }
        }
    }

    private void hookOnCombiPreferenceClickListener(ClassLoader classLoader, DexKitCacheBridge.RecyclableBridge bridge, Hooker isInternationalBuildHooker) {
        var onLeftSideClickedMatcher = MethodMatcher.create()
                .name("onLeftSideClicked")
                .paramCount(0)
                .anyOf(
                        MethodMatcher.create()
                                .addInvoke("Lcom/android/settings/applications/credentials/CombinedProviderInfo;->launchSettingsActivityIntent(Landroid/content/Context;Ljava/lang/CharSequence;Ljava/lang/CharSequence;I)Z"),
                        MethodMatcher.create()
                                .addInvoke("Lcom/android/settings/applications/credentials/CombinedProviderInfo;->launchSettingsActivityIntent(Landroid/content/Context;Ljava/lang/CharSequence;Ljava/lang/CharSequence;I)V"));
        bridge.getMethods(FindMethod.create()
                .searchPackages("com.android.settings.applications.credentials")
                .matcher(onLeftSideClickedMatcher)
        ).forEach(methodData -> {
            try {
                var aMethod = methodData.getMethodInstance(classLoader);
                deoptimize(aMethod);
                hookE(aMethod).intercept(isInternationalBuildHooker);
            } catch (NoSuchMethodException e) {
                log(Log.ERROR, TAG, "hook onLeftSideClicked failed", e);
            }
        });
    }

    private void deoptimizeMethods(Class<?> clazz, String... names) {
        var list = Arrays.asList(names);
        Arrays.stream(clazz.getDeclaredMethods())
                .filter(method -> list.contains(method.getName()))
                .forEach(this::deoptimize);
    }

    private void hookRequestSession(ClassLoader classLoader) throws NoSuchMethodException, ClassNotFoundException, NoSuchFieldException {
        var cRequestSession = classLoader.loadClass("com.android.server.credentials.RequestSession");
        try {
            deoptimizeMethods(classLoader.loadClass("com.android.server.credentials.ProviderGetSession"), "createNewSession");
            deoptimizeMethods(classLoader.loadClass("com.android.server.credentials.ProviderCreateSession"), "createNewSession");
        } catch (ClassNotFoundException e) {
            log(Log.WARN, TAG, "ProviderGetSession or ProviderCreateSession not found, skipping deoptimize", e);
        }
        var fHybridService = cRequestSession.getDeclaredField("mHybridService");
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
        hookE(constructorRequestSession).intercept(chain -> {
            chain.proceed();
            UnsafeUtils.INSTANCE.setObjectField(fHybridService, chain.getThisObject(),
                    "com.google.android.gms/.auth.api.credentials.credman.service.RemoteService");
            if (chain.getArgs().get(0) instanceof Context context) {
                ensurePreferred(context);
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
        hookE(mGetOemOverrideComponentName).intercept(chain -> {
            var args = chain.getArgs();
            if (args.size() >= 2 && args.get(0) instanceof Context context && args.get(1) instanceof IntentCreationResult.Builder intentResultBuilder) {
                ensurePreferred(context);
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

    private void securityCenterHook(ClassLoader classLoader, DexKitCacheBridge.RecyclableBridge bridge) {
        var classDataList = new ArrayList<ClassData>();
        bridge.withBridge(rawBridge -> {
            var classData = rawBridge.getClassData("Lcom/miui/securitycenter/Application;");
            if (classData != null)
                classDataList.add(classData);
            classData = rawBridge.getClassData("Lcom/miui/securitycenter/service/CacheService;");
            if (classData != null)
                classDataList.add(classData);
        });
        try {
            var mSetStringResourceConfigIfNeed = bridge.getMethod(FindMethod.create()
                    .searchInClass(classDataList)
                    .matcher(MethodMatcher.create()
                            .anyOf(
                                    MethodMatcher.create()
                                            .paramTypes(Context.class, String.class, int.class),
                                    MethodMatcher.create()
                                            .paramTypes(String.class, int.class))
                            .addInvoke("Landroid/content/res/Resources;->getString(I)Ljava/lang/String;")
                            .addInvoke("Landroid/provider/Settings$Secure;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z")
                    ));
            var setStringResourceConfigIfNeedMethodInstance = mSetStringResourceConfigIfNeed.getMethodInstance(classLoader);
            deoptimize(setStringResourceConfigIfNeedMethodInstance);
            var mConfigForAutofillService = bridge.getMethod(FindMethod.create()
                    .searchInClass(classDataList)
                    .matcher(MethodMatcher.create()
                            .addEqString("autofill_service")
                            .addInvoke(mSetStringResourceConfigIfNeed.toString())
                    ));
            hookE(mConfigForAutofillService.getMethodInstance(classLoader)).intercept(chain -> null);
        } catch (NoSuchMethodException e) {
            log(Log.WARN, TAG, "hook configForAutofillService", e);
        } catch (NoResultException e) {
            log(Log.WARN, TAG, "dexkit search err", e);
        }
        try {
            var mSetStringArrayResourceConfigIfNeed = bridge.getMethod(FindMethod.create()
                    .searchInClass(classDataList)
                    .matcher(MethodMatcher.create()
                            .anyOf(
                                    MethodMatcher.create()
                                            .paramTypes(Context.class, String.class, int.class),
                                    MethodMatcher.create()
                                            .paramTypes(String.class, int.class))
                            .addInvoke("Landroid/content/res/Resources;->getStringArray(I)[Ljava/lang/String;")
                            .addInvoke("Landroid/provider/Settings$Secure;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z")
                    ));
            var setStringArrayResourceConfigIfNeedMethodInstance = mSetStringArrayResourceConfigIfNeed.getMethodInstance(classLoader);
            deoptimize(setStringArrayResourceConfigIfNeedMethodInstance);
            var mSetDefaultConfigForAutofillAndCredentialManager = bridge.getMethod(FindMethod.create()
                    .searchInClass(classDataList)
                    .matcher(MethodMatcher.create()
                            .usingEqStrings("credential_service", "credential_service_primary")
                            .addInvoke(mSetStringArrayResourceConfigIfNeed.toString())
                    ));
            hookE(mSetDefaultConfigForAutofillAndCredentialManager.getMethodInstance(classLoader)).intercept(chain -> null);
        } catch (NoSuchMethodException e) {
            log(Log.ERROR, TAG, "hook setDefaultConfigForAutofillAndCredentialManager", e);
        } catch (NoResultException e) {
            log(Log.ERROR, TAG, "dexkit search err", e);
        }
    }

    @Nullable
    private static String getPreferredPackage() {
        var p = prefs;
        if (p == null) return null;
        var pkg = p.getString(PREF_KEY_PREFERRED_PACKAGE, null);
        return pkg == null || pkg.isBlank() ? null : pkg.trim();
    }

    @Nullable
    private static String findServiceComponent(@NonNull Context context, @NonNull String packageName,
                                               @NonNull String action) {
        var pm = context.getPackageManager();
        var intent = new Intent(action);
        intent.setPackage(packageName);
        for (var info : pm.queryIntentServices(intent, PackageManager.GET_META_DATA)) {
            var si = info.serviceInfo;
            if (si != null && packageName.equals(si.packageName)) {
                return new ComponentName(si.packageName, si.name).flattenToString();
            }
        }
        return null;
    }

    @Nullable
    private static ComponentName getPreferredCredentialService() {
        var pkg = getPreferredPackage();
        if (pkg == null) return null;
        var cachedPkg = cachedPreferredPkg;
        var cachedCn = cachedPreferredCredentialService;
        if (pkg.equals(cachedPkg) && cachedCn != null) return cachedCn;
        Context context = null;
        try {
            context = ActivityThread.currentActivityThread().getSystemContext();
        } catch (Throwable ignored) {
        }
        if (context == null) return null;
        var component = findServiceComponent(context, pkg, CREDENTIAL_SERVICE_ACTION);
        var cn = component == null ? null : ComponentName.unflattenFromString(component);
        cachedPreferredPkg = pkg;
        cachedPreferredCredentialService = cn;
        return cn;
    }

    /**
     * 确保所选密码管理软件为首选凭据提供者与自动填充服务(幂等)。
     * 在 system_server 进程中调用,每次凭据请求/选择器生成时校正,
     * 作为手机管家重置行为的兜底;未选择或所选软件不可用时不做任何干预。
     */
    private static void ensurePreferred(@NonNull Context context) {
        var pkg = getPreferredPackage();
        if (pkg == null) return;
        var credentialService = findServiceComponent(context, pkg, CREDENTIAL_SERVICE_ACTION);
        if (credentialService == null) {
            Log.w(TAG, "selected package has no credential provider service: " + pkg);
            return;
        }
        try {
            var cr = context.getContentResolver();
            // 凭据提供者列表:确保所选项在列,保留其他已启用项
            var list = Settings.Secure.getString(cr, "credential_service");
            if (list == null || list.isBlank()) {
                Settings.Secure.putString(cr, "credential_service", credentialService);
            } else if (!containsComponent(list, credentialService)) {
                var sep = list.contains(";") && !list.contains(",") ? ";" : ",";
                Settings.Secure.putString(cr, "credential_service", credentialService + sep + list);
            }
            // 首选凭据提供者
            var primary = Settings.Secure.getString(cr, "credential_service_primary");
            if (!sameComponent(primary, credentialService)) {
                Settings.Secure.putString(cr, "credential_service_primary", credentialService);
            }
            // 自动填充服务:仅当所选软件提供自动填充服务时切换
            var autofillService = findServiceComponent(context, pkg, AUTOFILL_SERVICE_ACTION);
            if (autofillService != null) {
                var autofill = Settings.Secure.getString(cr, "autofill_service");
                if (!sameComponent(autofill, autofillService)) {
                    Settings.Secure.putString(cr, "autofill_service", autofillService);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ensure preferred provider failed", e);
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

    private record IsInternationalBuildHooker(
            Field fIsInternationalBuildBoolean) implements Hooker {
        private static final ReentrantLock INTL_LOCK = new ReentrantLock(true); // fair optional
        private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
        private static final ThreadLocal<Boolean> PREV_VALUE = new ThreadLocal<>();
        private static final UnsafeUtils UNSAFE = UnsafeUtils.INSTANCE;

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
                        UNSAFE.setStaticBooleanField(fIsInternationalBuildBoolean, true);
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
                            UNSAFE.setStaticBooleanField(fIsInternationalBuildBoolean, prev);
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
