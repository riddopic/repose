/*
 * #%L
 * Repose
 * %%
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.openrepose.core.services.datastore.distributed;

import org.openrepose.commons.utils.encoding.EncodingProvider;
import org.openrepose.core.services.RequestProxyService;

public class ClusterConfiguration {

    private RequestProxyService proxyService;
    private EncodingProvider encodingProvider;
    private ClusterView clusterView;

    public ClusterConfiguration(RequestProxyService proxyService, EncodingProvider encodingProvider, ClusterView clusterView) {
        this.proxyService = proxyService;
        this.encodingProvider = encodingProvider;
        this.clusterView = clusterView;
    }

    public RequestProxyService getProxyService() {
        return proxyService;
    }

    public EncodingProvider getEncodingProvider() {
        return encodingProvider;
    }

    public ClusterView getClusterView() {
        return clusterView;
    }
}
