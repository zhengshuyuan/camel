/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.jms;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.jms.ConnectionFactory;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeTimedOutException;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.test.CamelTestSupport;
import org.junit.Ignore;

import static org.apache.camel.component.jms.JmsComponent.jmsComponentClientAcknowledge;

/**
 * @version $Revision$
 */
public class JmsRouteRequestReplyTest extends CamelTestSupport {

    protected static final String REPLY_TO_DESTINATION_SELECTOR_NAME = "camelProducer";
    protected static String componentName = "amq";
    protected static String componentName1 = "amq1";
    protected static String endpointUriA = componentName + ":queue:test.a";
    protected static String endpointUriB = componentName + ":queue:test.b";
    protected static String endpointUriB1 = componentName1 + ":queue:test.b";
    // note that the replyTo both A and B endpoints share the persistent replyTo queue,
    // which is one more way to verify that reply listeners of A and B endpoints don't steal each other messages
    protected static String endpointReplyToUriA = componentName + ":queue:test.a?replyTo=queue:test.a.reply";
    protected static String endpointReplyToUriB = componentName + ":queue:test.b?replyTo=queue:test.a.reply";
    protected static String request = "Hello World";
    protected static String expectedReply = "Re: " + request;
    protected static int maxTasks = 100;
    protected static int maxServerTasks = 1/*maxTasks / 5*/;
    protected static int maxCalls = 10;
    protected static AtomicBoolean inited = new AtomicBoolean(false);
    protected static Map<String, ContextBuilder> contextBuilders = new HashMap<String, ContextBuilder>();
    protected static Map<String, RouteBuilder> routeBuilders = new HashMap<String, RouteBuilder>();

    private interface ContextBuilder {
        CamelContext buildContext(CamelContext context) throws Exception;
    }

    public static class SingleNodeDeadEndRouteBuilder extends RouteBuilder {
        public void configure() throws Exception {
            from(endpointUriA).process(new Processor() {
                public void process(Exchange e) {
                    // do nothing
                }
            });
        }
    };

    public static class SingleNodeRouteBuilder extends RouteBuilder {
        public void configure() throws Exception {
            from(endpointUriA).process(new Processor() {
                public void process(Exchange e) {
                    String request = e.getIn().getBody(String.class);
                    e.getOut().setBody(expectedReply + request.substring(request.indexOf('-')));
                }
            });
        }
    };

    public static class MultiNodeRouteBuilder extends RouteBuilder {
        public void configure() throws Exception {
            from(endpointUriA).to(endpointUriB);
            from(endpointUriB).process(new Processor() {
                public void process(Exchange e) {
                    String request = e.getIn().getBody(String.class);
                    e.getOut().setBody(expectedReply + request.substring(request.indexOf('-')));
                }
            });
        }
    };

    public static class MultiNodeReplyToRouteBuilder extends RouteBuilder {
        public void configure() throws Exception {
            from(endpointUriA).to(endpointReplyToUriB);
            from(endpointUriB).process(new Processor() {
                public void process(Exchange e) {
                    Message in = e.getIn();
                    Message out = e.getOut();
                    String selectorValue = in.getHeader(REPLY_TO_DESTINATION_SELECTOR_NAME, String.class);
                    String request = in.getBody(String.class);
                    out.setHeader(REPLY_TO_DESTINATION_SELECTOR_NAME, selectorValue);
                    out.setBody(expectedReply + request.substring(request.indexOf('-')));
                }
            });
        }
    };

    public static class MultiNodeDiffCompRouteBuilder extends RouteBuilder {
        public void configure() throws Exception {
            from(endpointUriA).to(endpointUriB1);
            from(endpointUriB1).process(new Processor() {
                public void process(Exchange e) {
                    String request = e.getIn().getBody(String.class);
                    e.getOut().setBody(expectedReply + request.substring(request.indexOf('-')));
                }
            });
        }
    };

    public static class ContextBuilderMessageID implements ContextBuilder {
        public CamelContext buildContext(CamelContext context) throws Exception {
            ConnectionFactory connectionFactory =
                new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
            JmsComponent jmsComponent = jmsComponentClientAcknowledge(connectionFactory);
            jmsComponent.setUseMessageIDAsCorrelationID(true);
            jmsComponent.setConcurrentConsumers(maxServerTasks);
            /*
            jmsComponent.getConfiguration().setRequestTimeout(600000);
            jmsComponent.getConfiguration().setRequestMapPurgePollTimeMillis(30000);
             */
            context.addComponent(componentName, jmsComponent);
            return context;
        }
    };

    protected static void init() {
        if (inited.compareAndSet(false, true)) {

            ContextBuilder contextBuilderMessageID = new ContextBuilderMessageID();

            ContextBuilder contextBuilderCorrelationID = new ContextBuilder() {
                public CamelContext buildContext(CamelContext context) throws Exception {
                    ConnectionFactory connectionFactory =
                        new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
                    JmsComponent jmsComponent = jmsComponentClientAcknowledge(connectionFactory);
                    jmsComponent.setUseMessageIDAsCorrelationID(false);
                    jmsComponent.setConcurrentConsumers(maxServerTasks);
                    /*
                    jmsComponent.getConfiguration().setRequestTimeout(600000);
                    jmsComponent.getConfiguration().setRequestMapPurgePollTimeMillis(60000);
                    */
                    context.addComponent(componentName, jmsComponent);
                    return context;
                }
            };

            ContextBuilder contextBuilderMessageIDNamedReplyToSelector = new ContextBuilder() {
                public CamelContext buildContext(CamelContext context) throws Exception {
                    ConnectionFactory connectionFactory =
                        new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
                    JmsComponent jmsComponent = jmsComponentClientAcknowledge(connectionFactory);
                    jmsComponent.setUseMessageIDAsCorrelationID(true);
                    jmsComponent.setConcurrentConsumers(maxServerTasks);
                    jmsComponent.getConfiguration().setReplyToDestinationSelectorName(REPLY_TO_DESTINATION_SELECTOR_NAME);
                    context.addComponent(componentName, jmsComponent);
                    return context;
                }
            };

            ContextBuilder contextBuilderCorrelationIDNamedReplyToSelector = new ContextBuilder() {
                public CamelContext buildContext(CamelContext context) throws Exception {
                    ConnectionFactory connectionFactory =
                        new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
                    JmsComponent jmsComponent = jmsComponentClientAcknowledge(connectionFactory);
                    jmsComponent.setUseMessageIDAsCorrelationID(false);
                    jmsComponent.setConcurrentConsumers(maxServerTasks);
                    jmsComponent.getConfiguration().setReplyToDestinationSelectorName(REPLY_TO_DESTINATION_SELECTOR_NAME);
                    context.addComponent(componentName, jmsComponent);
                    return context;
                }
            };


            ContextBuilder contextBuilderCorrelationIDDiffComp = new ContextBuilder() {
                public CamelContext buildContext(CamelContext context) throws Exception {
                    ConnectionFactory connectionFactory =
                        new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
                    JmsComponent jmsComponent = jmsComponentClientAcknowledge(connectionFactory);
                    jmsComponent.setUseMessageIDAsCorrelationID(false);
                    jmsComponent.setConcurrentConsumers(maxServerTasks);
                    context.addComponent(componentName, jmsComponent);
                    jmsComponent = jmsComponentClientAcknowledge(connectionFactory);
                    jmsComponent.setUseMessageIDAsCorrelationID(false);
                    jmsComponent.setConcurrentConsumers(maxServerTasks);
                    context.addComponent(componentName1, jmsComponent);
                    return context;
                }
            };

            ContextBuilder contextBuilderMessageIDDiffComp = new ContextBuilder() {
                public CamelContext buildContext(CamelContext context) throws Exception {
                    ConnectionFactory connectionFactory =
                        new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
                    JmsComponent jmsComponent = jmsComponentClientAcknowledge(connectionFactory);
                    jmsComponent.setUseMessageIDAsCorrelationID(true);
                    jmsComponent.setConcurrentConsumers(maxServerTasks);
                    context.addComponent(componentName, jmsComponent);
                    jmsComponent = jmsComponentClientAcknowledge(connectionFactory);
                    jmsComponent.setUseMessageIDAsCorrelationID(true);
                    jmsComponent.setConcurrentConsumers(maxServerTasks);
                    context.addComponent(componentName1, jmsComponent);
                    return context;
                }
            };


            contextBuilders.put("testUseMessageIDAsCorrelationID", contextBuilderMessageID);

            contextBuilders.put("testUseCorrelationID", contextBuilderCorrelationID);
            contextBuilders.put("testUseMessageIDAsCorrelationIDMultiNode", contextBuilderMessageID);
            contextBuilders.put("testUseCorrelationIDMultiNode", contextBuilderCorrelationID);

            contextBuilders.put("testUseMessageIDAsCorrelationIDPersistReplyToMultiNode", contextBuilderMessageID);
            contextBuilders.put("testUseCorrelationIDPersistReplyToMultiNode", contextBuilderCorrelationID);

            contextBuilders.put("testUseMessageIDAsCorrelationIDPersistMultiReplyToMultiNode", contextBuilderMessageID);
            contextBuilders.put("testUseCorrelationIDPersistMultiReplyToMultiNode", contextBuilderCorrelationID);

            contextBuilders.put("testUseMessageIDAsCorrelationIDPersistMultiReplyToWithNamedSelectorMultiNode",
                                 contextBuilderMessageIDNamedReplyToSelector);

            contextBuilders.put("testUseCorrelationIDPersistMultiReplyToWithNamedSelectorMultiNode",
                                 contextBuilderCorrelationIDNamedReplyToSelector);

            contextBuilders.put("testUseCorrelationIDMultiNodeDiffComponents", contextBuilderCorrelationIDDiffComp);
            contextBuilders.put("testUseMessageIDAsCorrelationIDMultiNodeDiffComponents", contextBuilderMessageIDDiffComp);
            contextBuilders.put("testUseMessageIDAsCorrelationIDTimeout", contextBuilderMessageID);
            contextBuilders.put("testUseCorrelationIDTimeout", contextBuilderMessageID);

            routeBuilders.put("testUseMessageIDAsCorrelationID", new SingleNodeRouteBuilder());
            routeBuilders.put("testUseMessageIDAsCorrelationIDReplyToTempDestinationPerComponent", new SingleNodeRouteBuilder());
            routeBuilders.put("testUseMessageIDAsCorrelationIDReplyToTempDestinationPerProducer", new SingleNodeRouteBuilder());
            routeBuilders.put("testUseCorrelationID", new SingleNodeRouteBuilder());
            routeBuilders.put("testUseMessageIDAsCorrelationIDMultiNode", new MultiNodeRouteBuilder());
            routeBuilders.put("testUseCorrelationIDMultiNode", new MultiNodeRouteBuilder());

            routeBuilders.put("testUseMessageIDAsCorrelationIDPersistReplyToMultiNode", new MultiNodeRouteBuilder());
            routeBuilders.put("testUseCorrelationIDPersistReplyToMultiNode", new MultiNodeRouteBuilder());

            routeBuilders.put("testUseMessageIDAsCorrelationIDPersistMultiReplyToMultiNode", new MultiNodeReplyToRouteBuilder());
            routeBuilders.put("testUseCorrelationIDPersistMultiReplyToMultiNode", new MultiNodeReplyToRouteBuilder());

            routeBuilders.put("testUseMessageIDAsCorrelationIDPersistMultiReplyToWithNamedSelectorMultiNode",
                               new MultiNodeReplyToRouteBuilder());

            routeBuilders.put("testUseCorrelationIDPersistMultiReplyToWithNamedSelectorMultiNode",
                               new MultiNodeReplyToRouteBuilder());

            routeBuilders.put("testUseCorrelationIDMultiNodeDiffComponents", new MultiNodeDiffCompRouteBuilder());
            routeBuilders.put("testUseMessageIDAsCorrelationIDMultiNodeDiffComponents", new MultiNodeDiffCompRouteBuilder());
            routeBuilders.put("testUseMessageIDAsCorrelationIDTimeout", new SingleNodeDeadEndRouteBuilder());
            routeBuilders.put("testUseCorrelationIDTimeout", new SingleNodeDeadEndRouteBuilder());
        }
    }

    public class Task extends Thread {
        private AtomicInteger counter;
        private String fromUri;
        private volatile boolean ok = true;
        private volatile String message = "";

        public Task(AtomicInteger counter, String fromUri) {
            this.counter = counter;
            this.fromUri = fromUri;
        }

        public void run() {
            // template must be started
            try {
                template.start();
            } catch (Exception e) {
                // ignore
            }

            for (int i = 0; i < maxCalls; i++) {
                int callId = counter.incrementAndGet();
                Object reply = "";
                try {
                    reply = template.requestBody(fromUri, request + "-" + callId);
                } catch (RuntimeCamelException e) {
                    // expected in some cases
                }
                if (!reply.equals(expectedReply + "-" + callId)) {
                    ok = false;
                    message = "Unexpected reply. Expected: '" + expectedReply  + "-" + callId
                              + "'; Received: '" +  reply + "'";
                }
            }
        }
        public void assertSuccess() {
            assertTrue(message, ok);
        }
    }

    @Override
    protected void setUp() throws Exception {
        init();
        super.setUp();
        Thread.sleep(1000);
    }

    public void testUseMessageIDAsCorrelationID() throws Exception {
        runRequestReplyThreaded(endpointUriA);
    }

    public void testUseCorrelationID() throws Exception {
        runRequestReplyThreaded(endpointUriA);
    }

    public void testUseMessageIDAsCorrelationIDMultiNode() throws Exception {
        runRequestReplyThreaded(endpointUriA);
    }

    public void testUseCorrelationIDMultiNode() throws Exception {
        runRequestReplyThreaded(endpointUriA);
    }

    public void testUseMessageIDAsCorrelationIDPersistReplyToMultiNode() throws Exception {
        runRequestReplyThreaded(endpointReplyToUriA);
    }

    public void testUseCorrelationIDPersistReplyToMultiNode() throws Exception {
        runRequestReplyThreaded(endpointUriA);
    }

    // (1)
    // note this is an inefficient way of correlating replies to a persistent queue
    // a consumer will have to be created for each reply message
    // see testUseMessageIDAsCorrelationIDPersistMultiReplyToWithNamedSelectorMultiNode
    // or testCorrelationIDPersistMultiReplyToWithNamedSelectorMultiNode
    // for a faster way to do this. Note however that in this case the message copy has to occur
    // between consumer -> producer as the selector value needs to be propagated to the ultimate
    // destination, which in turn will copy this value back into the reply message
    public void testUseMessageIDAsCorrelationIDPersistMultiReplyToMultiNode() throws Exception {
        int oldMaxTasks = maxTasks;
        int oldMaxServerTasks = maxServerTasks;
        int oldMaxCalls = maxCalls;

        maxTasks = 10;
        maxServerTasks = 1;
        maxCalls = 2;

        try {
            runRequestReplyThreaded(endpointUriA);
        } finally {
            maxTasks = oldMaxTasks;
            maxServerTasks = oldMaxServerTasks;
            maxCalls = oldMaxCalls;
        }
    }

    // see (1)
    public void testUseCorrelationIDPersistMultiReplyToMultiNode() throws Exception {
        int oldMaxTasks = maxTasks;
        int oldMaxServerTasks = maxServerTasks;
        int oldMaxCalls = maxCalls;

        maxTasks = 10;
        maxServerTasks = 1;
        maxCalls = 2;

        try {
            runRequestReplyThreaded(endpointUriA);
        } finally {
            maxTasks = oldMaxTasks;
            maxServerTasks = oldMaxServerTasks;
            maxCalls = oldMaxCalls;
        }
    }

    public void testUseMessageIDAsCorrelationIDPersistMultiReplyToWithNamedSelectorMultiNode() throws Exception {
        runRequestReplyThreaded(endpointUriA);
    }

    public void testUseCorrelationIDPersistMultiReplyToWithNamedSelectorMultiNode() throws Exception {
        runRequestReplyThreaded(endpointUriA);
    }

    public void testUseCorrelationIDTimeout() throws Exception {
        JmsComponent c = (JmsComponent)context.getComponent(componentName);
        c.getConfiguration().setRequestTimeout(1000);

        Object reply = "";
        try {
            reply = template.requestBody(endpointUriA, request);
            fail("Should have thrown exception");
        } catch (RuntimeCamelException e) {
            assertIsInstanceOf(ExchangeTimedOutException.class, e.getCause());
        }
        assertEquals("", reply);
    }

    public void testUseMessageIDAsCorrelationIDTimeout() throws Exception {
        JmsComponent c = (JmsComponent)context.getComponent(componentName);
        c.getConfiguration().setRequestTimeout(1000);

        Object reply = "";
        try {
            reply = template.requestBody(endpointUriA, request);
            fail("Should have thrown exception");
        } catch (RuntimeCamelException e) {
            assertIsInstanceOf(ExchangeTimedOutException.class, e.getCause());
        }
        assertEquals("", reply);
    }

    public void testUseCorrelationIDMultiNodeDiffComponents() throws Exception {
        runRequestReplyThreaded(endpointUriA);
    }

    public void testUseMessageIDAsCorrelationIDMultiNodeDiffComponents() throws Exception {
        runRequestReplyThreaded(endpointUriA);
    }

    protected void runRequestReplyThreaded(String fromUri) throws Exception {
        final AtomicInteger counter = new AtomicInteger(-1);
        Task[] tasks = new Task[maxTasks];
        for (int i = 0; i < maxTasks; ++i) {
            Task task = new Task(counter, fromUri);
            tasks[i] = task;
            task.start();
        }
        for (int i = 0; i < maxTasks; ++i) {
            tasks[i].join();
            tasks[i].assertSuccess();
        }
    }

    protected CamelContext createCamelContext() throws Exception {
        CamelContext camelContext = super.createCamelContext();
        return contextBuilders.get(getName()).buildContext(camelContext);
    }

    protected RouteBuilder createRouteBuilder() throws Exception {
        return routeBuilders.get(getName());
    }
}
