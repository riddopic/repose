/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
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
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package org.openrepose.filters.versioning;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.xml.sax.SAXParseException;

import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;

/**
 * @author kush5342
 */
@RunWith(Enclosed.class)
public class VersioningSchemaTest {

    public static class WhenValidatingVersioningConfiguration {

        private Validator validator;

        @Before
        public void standUp() throws Exception {
            SchemaFactory factory = SchemaFactory.newInstance("http://www.w3.org/XML/XMLSchema/v1.1");
            factory.setFeature("http://apache.org/xml/features/validation/cta-full-xpath-checking", true);

            Schema schema = factory.newSchema(
                    new StreamSource[]{
                            new StreamSource(VersioningSchemaTest.class.getResourceAsStream("/META-INF/schema/config/versioning-configuration.xsd"))
                    });

            validator = schema.newValidator();
        }

        @Test
        public void shouldValidateExampleConfig() throws Exception {
            final StreamSource sampleSource = new StreamSource(VersioningSchemaTest.class.getResourceAsStream("/META-INF/schema/examples/versioning.cfg.xml"));
            validator.validate(sampleSource);
        }


        @Test
        public void shouldFailIfMutipleVersionMappingHasSameID() throws Exception {
            String xml =
                    "<versioning xmlns=\"http://docs.openrepose.org/repose/versioning/v2.0\"> " +
                            "   <version-mapping id=\"v1\" pp-dest-id=\"service-v0\" status=\"DEPRECATED\"/>" +
                            " <version-mapping id=\"v1\" pp-dest-id=\"service-v1\">" +
                            " <media-types>\n" +
                            " <media-type base=\"application/xml\" type=\"application/vnd.vendor.service-v1+xml\"/>" +
                            " </media-types>\n" +
                            " </version-mapping>" +
                            "</versioning>";
            assertInvalidConfig(xml, "Version mapping must have ids unique within their containing filter list");
        }


        @Ignore
        @Test
        public void shouldFailIfMediatypeNotUniqueInOneVersionMapping() throws Exception {
            String xml =
                    "<versioning xmlns=\"http://docs.openrepose.org/repose/versioning/v2.0\"> " +
                            " <version-mapping id=\"v1\" pp-dest-id=\"service-v1\">" +
                            " <media-types>\n" +
                            " <media-type base=\"application/xml\" type=\"application/vnd.vendor.service-v1+xml\"/>" +
                            " <media-type base=\"application/xml\" type=\"application/vnd.vendor.service-v1+xml\"/>" +
                            " </media-types>\n" +
                            " </version-mapping>" +
                            "</versioning>";
            assertInvalidConfig(xml, "MediaTypes should be unique within a version-mapping.");
        }


        private void assertInvalidConfig(String xml, String errorMessage) {
            final StreamSource sampleSource = new StreamSource(new ByteArrayInputStream(xml.getBytes()));
            Exception caught = null;
            try {
                validator.validate(sampleSource);
            } catch (Exception e) {
                caught = e;
            }

            assertNotNull("Expected exception", caught);
            assertSame(SAXParseException.class, caught.getClass());

            assertThat(caught.getLocalizedMessage(), containsString(errorMessage));
        }

    }

}
