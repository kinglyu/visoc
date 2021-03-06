// ILocalRouterAIDL.aidl
package com.dovar.router_api;

import com.dovar.router_api.multiprocess.MultiRouterRequest;
import com.dovar.router_api.multiprocess.MultiRouterResponse;

interface ILocalRouterAIDL {
    MultiRouterResponse route(in MultiRouterRequest routerRequest);
    boolean stopWideRouter();
    void connectWideRouter();
}
