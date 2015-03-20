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
package org.openrepose.filters.destinationrouter;

import org.openrepose.commons.utils.StringUtilities;
import org.openrepose.commons.utils.servlet.http.ReadableHttpServletResponse;
import org.openrepose.core.filter.logic.FilterAction;
import org.openrepose.core.filter.logic.FilterDirector;
import org.openrepose.core.filter.logic.common.AbstractFilterLogicHandler;
import org.openrepose.core.filter.logic.impl.FilterDirectorImpl;
import org.openrepose.core.filters.DestinationRouter;
import org.openrepose.core.services.reporting.metrics.MetricsService;
import org.openrepose.core.services.reporting.metrics.impl.MeterByCategorySum;
import org.slf4j.Logger;

import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.TimeUnit;

public class RoutingTagger extends AbstractFilterLogicHandler {

    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(RoutingTagger.class);
    private String id;
    private double quality;
    private MetricsService metricsService;
    private MeterByCategorySum mbcsRoutedReponse;

    public RoutingTagger(String id, double quality, MetricsService metricsService) {
        this.quality = quality;
        this.id = id;
        this.metricsService = metricsService;

        if (metricsService != null) {
            mbcsRoutedReponse = metricsService.newMeterByCategorySum(DestinationRouter.class, "destination-router",
                    "Routed Response", TimeUnit.SECONDS);
        }
    }

    @Override
    public FilterDirector handleRequest(HttpServletRequest request, ReadableHttpServletResponse response) {
        final FilterDirector myDirector = new FilterDirectorImpl();
        myDirector.setFilterAction(FilterAction.PASS);

        if (StringUtilities.isBlank(id)) {
            LOG.warn("No Destination configured for Destination Router");
        } else {
            myDirector.addDestination(id, request.getRequestURI(), quality);
            if (mbcsRoutedReponse != null) {
                mbcsRoutedReponse.mark(id);
            }
        }

        return myDirector;
    }
}
