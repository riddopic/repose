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
package org.openrepose.commons.utils.http;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

/**
 *
 * 
 */
@RunWith(Enclosed.class)
public class HttpDateTest {
    public static class WhenMarshallingToRFC1123 {
        @Test
        public void shouldOutputCorrectFormat() {
            final String expected = "Sun, 06 Nov 1994 08:49:37 GMT";

            final Calendar then = Calendar.getInstance();
            then.setTimeZone(TimeZone.getTimeZone("GMT"));
            then.set(1994, 10, 6, 8, 49, 37);

            assertEquals("Date format did not match expected", expected, new HttpDate(then.getTime()).toRFC1123());
        }
    }
}
