/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.endpoint.healthcheck;

import static com.linecorp.armeria.common.metric.MeterRegistryUtil.measure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HttpHealthCheckService;
import com.linecorp.armeria.testing.server.ServerRule;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.prometheus.PrometheusMeterRegistry;

public class HttpHealthCheckedEndpointGroupTest {

    private static final String HEALTH_CHECK_PATH = "/healthcheck";

    private static class HealthCheckServerRule extends ServerRule {

        protected HealthCheckServerRule() {
            super(false); // Disable auto-start.
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(HEALTH_CHECK_PATH, new HttpHealthCheckService());
        }
    }

    private final MeterRegistry registry = new PrometheusMeterRegistry();

    @Rule
    public final ServerRule serverOne = new HealthCheckServerRule();

    @Rule
    public final ServerRule serverTwo = new HealthCheckServerRule();

    @Test
    public void endpoints() throws Exception {
        serverOne.start();
        serverTwo.start();

        final int portOne = serverOne.httpPort();
        final int portTwo = serverTwo.httpPort();
        final HealthCheckedEndpointGroup endpointGroup = HttpHealthCheckedEndpointGroup.of(
                new StaticEndpointGroup(Endpoint.of("127.0.0.1", portOne),
                                        Endpoint.of("127.0.0.1", portTwo)),
                HEALTH_CHECK_PATH);

        endpointGroup.newMeterBinder(registry, "foo").bindTo(registry);

        await().untilAsserted(
                () -> assertThat(endpointGroup.endpoints()).containsExactly(
                        Endpoint.of("127.0.0.1", portOne),
                        Endpoint.of("127.0.0.1", portTwo)));
        assertThat(measure(registry, "armeria_client_endpoint_group_count",
                           "name", "foo", "state", "healthy")).isEqualTo(2);
        assertThat(measure(registry, "armeria_client_endpoint_group_count",
                           "name", "foo", "state", "unhealthy")).isZero();
        assertThat(measure(registry, "armeria_client_endpoint_group_healthy",
                           "name", "foo", "authority", "127.0.0.1:" + portOne)).isOne();
        assertThat(measure(registry, "armeria_client_endpoint_group_healthy",
                           "name", "foo", "authority", "127.0.0.1:" + portTwo)).isOne();

        serverTwo.stop().get();
        await().untilAsserted(
                () -> assertThat(endpointGroup.endpoints()).containsExactly(
                        Endpoint.of("127.0.0.1", portOne)));
        assertThat(measure(registry, "armeria_client_endpoint_group_count",
                           "name", "foo", "state", "healthy")).isOne();
        assertThat(measure(registry, "armeria_client_endpoint_group_healthy",
                           "name", "foo", "authority", "127.0.0.1:" + portTwo)).isZero();
    }

    @Test
    public void endpoints_containsUnhealthyServer() throws Exception {
        serverOne.start();

        final int portOne = serverOne.httpPort();
        final int portTwo = 65535;
        final HealthCheckedEndpointGroup endpointGroup = HttpHealthCheckedEndpointGroup.of(
                new StaticEndpointGroup(Endpoint.of("127.0.0.1", portOne),
                                        Endpoint.of("127.0.0.1", portTwo)),
                HEALTH_CHECK_PATH);

        endpointGroup.newMeterBinder(registry, "bar").bindTo(registry);

        await().untilAsserted(
                () -> assertThat(endpointGroup.endpoints())
                        .containsOnly(Endpoint.of("127.0.0.1", portOne)));
        assertThat(measure(registry, "armeria_client_endpoint_group_count",
                           "name", "bar", "state", "healthy")).isOne();
        assertThat(measure(registry, "armeria_client_endpoint_group_count",
                           "name", "bar", "state", "unhealthy")).isOne();
        assertThat(measure(registry, "armeria_client_endpoint_group_healthy",
                           "name", "bar", "authority", "127.0.0.1:" + portOne)).isOne();
        assertThat(measure(registry, "armeria_client_endpoint_group_healthy",
                           "name", "bar", "authority", "127.0.0.1:" + portTwo)).isZero();
    }

    @Test
    public void endpoints_duplicateEntries() throws Exception {
        serverOne.start();

        final int portOne = serverOne.httpPort();
        final HealthCheckedEndpointGroup endpointGroup = HttpHealthCheckedEndpointGroup.of(
                new StaticEndpointGroup(Endpoint.of("127.0.0.1", portOne),
                                        Endpoint.of("127.0.0.1", portOne),
                                        Endpoint.of("127.0.0.1", portOne)),
                HEALTH_CHECK_PATH);

        endpointGroup.newMeterBinder(registry, "baz").bindTo(registry);

        await().untilAsserted(
                () -> assertThat(endpointGroup.endpoints())
                        .containsOnly(Endpoint.of("127.0.0.1", portOne)));

        assertThat(measure(registry, "armeria_client_endpoint_group_count",
                           "name", "baz", "state", "healthy")).isEqualTo(3);
        assertThat(measure(registry, "armeria_client_endpoint_group_count",
                           "name", "baz", "state", "unhealthy")).isZero();
        assertThat(measure(registry, "armeria_client_endpoint_group_healthy",
                           "name", "baz", "authority", "127.0.0.1:" + portOne)).isOne();
    }
}
