package com.capacitor.ncvplugin;

import com.getcapacitor.Logger;

public class NCVplugin {

    public String echo(String value) {
        Logger.info("Echo", value);
        return value;
    }
}
