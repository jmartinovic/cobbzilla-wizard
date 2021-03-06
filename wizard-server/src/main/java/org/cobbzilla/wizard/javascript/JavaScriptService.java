package org.cobbzilla.wizard.javascript;

import lombok.Getter;
import org.cobbzilla.util.javascript.JsEngine;
import org.cobbzilla.util.javascript.JsEngineConfig;

public abstract class JavaScriptService {

    public abstract JsEngineConfig getConfig();

    @Getter(lazy=true) private final JsEngine js = initJsEngine();

    protected JsEngine initJsEngine() { return new JsEngine(getConfig()); }

}
