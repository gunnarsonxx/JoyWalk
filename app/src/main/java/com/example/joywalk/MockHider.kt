package com.example.joywalk

import android.location.Location
import android.os.Bundle
import android.provider.Settings
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * LSPosed / Xposed entry point (listed in assets/xposed_init).
 *
 * Android stamps every location coming from a test provider as "mock", and a normal app
 * can't clear that. With root + LSPosed, this class is loaded inside each app you tick in
 * JoyWalk's LSPosed scope, and makes the mock checks those apps run come back clean:
 *  - Location.isFromMockProvider() / Location.isMock()  -> false
 *  - the "mockLocation" extra some Android versions/Play services add -> removed
 *  - the old Settings.Secure "mock_location" developer toggle -> "0"
 */
class MockHider : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == OWN_PACKAGE) {
            markActive(lpparam.classLoader)
            return
        }

        val loc = Location::class.java
        hook(loc, "isFromMockProvider", returnFalse)   // API 18–30 (deprecated after)
        hook(loc, "isMock", returnFalse)               // API 31+
        hook(loc, "getExtras", stripMockExtra)
        hook(loc, "setIsFromMockProvider", forceArgFalse)  // keep copies clean too
        hook(loc, "setMock", forceArgFalse)

        val secure = Settings.Secure::class.java
        hook(secure, "getString", mockSettingOff)
        hook(secure, "getInt", mockSettingOff)
        hook(secure, "getIntForUser", mockSettingOff)
        hook(secure, "getStringForUser", mockSettingOff)
    }

    private fun hook(cls: Class<*>, method: String, cb: XC_MethodHook) {
        try {
            XposedBridge.hookAllMethods(cls, method, cb)
        } catch (t: Throwable) {
            XposedBridge.log("JoyWalk: couldn't hook ${cls.simpleName}.$method: $t")
        }
    }

    /** Lets JoyWalk's own screen show whether the module is switched on. */
    private fun markActive(cl: ClassLoader) {
        try {
            val cls = cl.loadClass(ModuleStatus::class.java.name)
            hook(cls, "isActive", returnTrue)
        } catch (t: Throwable) {
            XposedBridge.log("JoyWalk: status hook failed: $t")
        }
    }

    companion object {
        private const val OWN_PACKAGE = "com.example.joywalk"
        private const val MOCK_EXTRA = "mockLocation"
        private const val MOCK_SETTING = "mock_location"

        private val returnFalse = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = false
            }
        }

        private val returnTrue = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = true
            }
        }

        private val forceArgFalse = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args
                if (args != null && args.isNotEmpty() && args[0] is Boolean) args[0] = false
            }
        }

        private val stripMockExtra = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val extras = param.result as? Bundle ?: return
                if (extras.containsKey(MOCK_EXTRA)) extras.remove(MOCK_EXTRA)
            }
        }

        // Settings.Secure.getString/getInt(resolver, name[, default][, user]) — name is args[1].
        private val mockSettingOff = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                if (args.size < 2 || args[1] != MOCK_SETTING) return
                val returnsString = (param.method as? Method)?.returnType == String::class.java
                param.result = if (returnsString) "0" else 0
            }
        }
    }
}

/** isActive() returns true only when LSPosed has loaded [MockHider] into JoyWalk itself. */
object ModuleStatus {
    @JvmStatic
    fun isActive(): Boolean = false
}
