/**
 * Copyright 2018-2024 The Jaeger Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.jaegertracing.tests;

import java.util.*;
import java.util.concurrent.TimeUnit;

import io.jaegertracing.internal.JaegerTracer;
import io.jaegertracing.tests.model.TestConfig;
import io.opentracing.Span;
import io.opentracing.tag.Tags;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CreateSpansRunnable implements Runnable {
    private boolean _printed = false;
    private long reporterEndTime;

    private static String[] HTTP_METHODS = {
            "get", "head", "post", "put", "delete", "options", "patch"
    };

    private static String[] EVENT_TYPES = {
            "info", "warn", "debug", "trace", "error", "fatal"
    };

    private boolean isValid() {
        return System.currentTimeMillis() < reporterEndTime;
    }

    class SpansReporer extends TimerTask {

        private long delay = 0;

        public SpansReporer() {
            delay = (1000L / config.getNumberOfSpans()); // delay in milliseconds
            if (delay > 10) {
                delay -= 1L; // remove 1ms delay from the actual delay
            }
        }

        @Override
        public void run() {
            if (!_printed) {
                _printed = true;
                logger.trace("Started sending spans, tracer:{}", name);
            }

            int count = 0;
            long startTime = System.currentTimeMillis();
            do {
                if (!isValid()) {
                    break;
                }
                count++;
                // emulate client spans
                Span span = tracer.buildSpan(name)
                        .withTag(Tags.COMPONENT.getKey(), "perf-test")
                        .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                        .withTag(Tags.HTTP_METHOD.getKey(), HTTP_METHODS[new Random().nextInt(HTTP_METHODS.length)])
                        .withTag(Tags.HTTP_STATUS.getKey(), new Random().nextInt(500))
                        .withTag(Tags.HTTP_URL.getKey(), String.format("http://www.example.com/foo/bar?q=bar&uuid=%s", UUID.randomUUID().toString()))
                        .start();
                for (int i = 0; i < 64; i++) {
                    Map<String, Object> logs = new HashMap<>();
                    logs.put("event", EVENT_TYPES[new Random().nextInt(EVENT_TYPES.length)]);
                    logs.put("event.log", String.format("Event at %tc with UUID %s", System.currentTimeMillis(), UUID.randomUUID().toString()));
                    span.log(logs);
                }
                span.finish();

                for (int j = 0; j < 3; j++) {
                    String operationName = name + j;
                    Span childSpan = tracer.buildSpan(operationName).asChildOf(span)
                            .withTag(Tags.COMPONENT.getKey(), "perf-test-child")
                            .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                            .withTag(Tags.HTTP_METHOD.getKey(), HTTP_METHODS[new Random().nextInt(HTTP_METHODS.length)])
                            .withTag(Tags.HTTP_STATUS.getKey(), new Random().nextInt(500))
                            .withTag(Tags.HTTP_URL.getKey(), String.format("http://www.example.com/foo/bar?q=bar&uuid=%s", UUID.randomUUID().toString()))
                            .start();

                    for (int i = 0; i < 5; i++) {
                        Map<String, Object> logs = new HashMap<>();
                        logs.put("event", EVENT_TYPES[new Random().nextInt(EVENT_TYPES.length)]);
                        logs.put("event.log", String.format("Event at %tc with UUID %s", System.currentTimeMillis(), UUID.randomUUID().toString()));
                        childSpan.log(logs);
                    }

                    childSpan.finish();
                }

                try {
                    TimeUnit.MILLISECONDS.sleep(delay);
                } catch (InterruptedException ex) {
                    logger.error("exception, ", ex);
                }
            } while (count < config.getNumberOfSpans());

            logger.trace("Reporting spans done, duration:{}ms, Tracer:{}",
                    System.currentTimeMillis() - startTime, name);
        }

    }

    private JaegerTracer tracer;
    private String name;
    private TestConfig config;
    private boolean close;

    public CreateSpansRunnable(JaegerTracer tracer, String name, TestConfig config, boolean close) {
        this.tracer = tracer;
        this.name = name;
        this.config = config;
        this.close = close;
    }

    @Override
    public void run() {
        logger.debug("Sending spans triggered for the tracer: {}", name);
        // update end time
        reporterEndTime = System.currentTimeMillis() + config.getSpansReportDurationInMillisecond();

        logger.debug("Sending spans triggered for the tracer: {}", name);
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new SpansReporer(), 0L, 1000L);
        try {
            while (isValid()) {
                TimeUnit.MILLISECONDS.sleep(500L);
            }
            timer.cancel();
        } catch (Exception ex) {
            logger.error("Exception,", ex);
        }

        if (close) {
            tracer.close();
        }
    }

}
