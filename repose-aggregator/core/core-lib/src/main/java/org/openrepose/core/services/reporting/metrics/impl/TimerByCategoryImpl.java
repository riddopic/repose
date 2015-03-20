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
package org.openrepose.core.services.reporting.metrics.impl;

import org.openrepose.core.services.reporting.metrics.MetricsService;
import org.openrepose.core.services.reporting.metrics.TimerByCategory;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Implements a collection of Timers which share the same JMX type & scope.  These Timers are usually related in some
 * fashion.
 * <p>
 * By calling the time(), stop(), and update() methods, a Timer object is automatically registered and can be updated
 * by later calls.
 * <p>
 * This is created by the {@link org.openrepose.core.services.reporting.metrics.impl.MetricsServiceImpl} factory class.
 * <p>
 * This class is thread-safe.
 *
 */
public class TimerByCategoryImpl implements TimerByCategory {

    private Map<String, Timer> map = new HashMap<String, Timer>();
    private MetricsService metricsService;
    private Class klass;
    private String scope;
    private TimeUnit duration;
    private TimeUnit rate;

    TimerByCategoryImpl(MetricsService metricsService, Class klass, String scope, TimeUnit duration,
                        TimeUnit rate) {
        this.metricsService = metricsService;
        this.klass = klass;
        this.scope = scope;
        this.duration = duration;
        this.rate = rate;
    }

    public void update(String key, long duration, TimeUnit unit) {
        verifyGet(key).update(duration, unit);
    }

    public TimerContext time(String key) {
        return verifyGet(key).time();
    }

    private Timer verifyGet(String key) {
        if (!map.containsKey(key)) {
            synchronized (this) {
                if (!map.containsKey(key)) {
                    map.put(key, metricsService.newTimer(klass, key, scope, duration, rate));
                }
            }
        }

        return map.get( key );
    }
}
