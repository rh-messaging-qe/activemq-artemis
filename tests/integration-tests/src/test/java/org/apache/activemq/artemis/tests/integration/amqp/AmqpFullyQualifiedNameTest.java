/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.tests.integration.amqp;

import java.util.HashMap;

import javax.jms.Connection;
import javax.jms.InvalidDestinationException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.Topic;

import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.core.postoffice.Binding;
import org.apache.activemq.artemis.core.postoffice.Bindings;
import org.apache.activemq.artemis.core.postoffice.impl.LocalQueueBinding;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.QueueQueryResult;
import org.apache.activemq.artemis.tests.util.Wait;
import org.apache.activemq.artemis.utils.CompositeAddress;
import org.junit.Before;
import org.junit.Test;

public class AmqpFullyQualifiedNameTest extends JMSClientTestSupport {

   private SimpleString anycastAddress = new SimpleString("address.anycast");
   private SimpleString multicastAddress = new SimpleString("address.multicast");

   private SimpleString anycastQ1 = new SimpleString("q1");
   private SimpleString anycastQ2 = new SimpleString("q2");
   private SimpleString anycastQ3 = new SimpleString("q3");

   private ServerLocator locator;

   @Override
   @Before
   public void setUp() throws Exception {
      super.setUp();

      locator = createNettyNonHALocator();
   }

   @Override
   protected void addAdditionalAcceptors(ActiveMQServer server) throws Exception {
      server.getConfiguration().addAcceptorConfiguration(new TransportConfiguration(NETTY_ACCEPTOR_FACTORY, new HashMap<String, Object>(), "netty", new HashMap<String, Object>()));
   }

   @Test(timeout = 60000)
   //there isn't much use of FQQN for topics
   //however we can test query functionality
   public void testTopic() throws Exception {

      Connection connection = createConnection(false);
      try {
         connection.setClientID("FQQNconn");
         connection.start();
         Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
         Topic topic = session.createTopic(multicastAddress.toString());

         MessageConsumer consumer1 = session.createConsumer(topic);
         MessageConsumer consumer2 = session.createConsumer(topic);
         MessageConsumer consumer3 = session.createConsumer(topic);

         MessageProducer producer = session.createProducer(topic);

         producer.send(session.createMessage());

         //each consumer receives one
         Message m = consumer1.receive(2000);
         assertNotNull(m);
         m = consumer2.receive(2000);
         assertNotNull(m);
         m = consumer3.receive(2000);
         assertNotNull(m);

         Bindings bindings = server.getPostOffice().getBindingsForAddress(multicastAddress);
         for (Binding b : bindings.getBindings()) {
            System.out.println("checking binidng " + b.getUniqueName() + " " + ((LocalQueueBinding)b).getQueue().getDeliveringMessages());
            SimpleString qName = b.getUniqueName();
            //do FQQN query
            QueueQueryResult result = server.queueQuery(CompositeAddress.toFullQN(multicastAddress, qName));
            assertTrue(result.isExists());
            assertEquals(result.getName(), CompositeAddress.toFullQN(multicastAddress, qName));
            //do qname query
            result = server.queueQuery(qName);
            assertTrue(result.isExists());
            assertEquals(result.getName(), qName);
         }
      } finally {
         connection.close();
      }
   }

   @Test
   public void testQueue() throws Exception {
      server.createQueue(anycastAddress, RoutingType.ANYCAST, anycastQ1, null, true, false, -1, false, true);
      server.createQueue(anycastAddress, RoutingType.ANYCAST, anycastQ2, null, true, false, -1, false, true);
      server.createQueue(anycastAddress, RoutingType.ANYCAST, anycastQ3, null, true, false, -1, false, true);

      Connection connection = createConnection();
      try {
         connection.start();
         Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

         javax.jms.Queue q1 = session.createQueue(CompositeAddress.toFullQN(anycastAddress, anycastQ1).toString());
         javax.jms.Queue q2 = session.createQueue(CompositeAddress.toFullQN(anycastAddress, anycastQ2).toString());
         javax.jms.Queue q3 = session.createQueue(CompositeAddress.toFullQN(anycastAddress, anycastQ3).toString());

         //send 3 messages to anycastAddress
         ClientSessionFactory cf = createSessionFactory(locator);
         ClientSession coreSession = cf.createSession();

         //send 3 messages
         ClientProducer coreProducer = coreSession.createProducer(anycastAddress);
         sendMessages(coreSession, coreProducer, 3);

         MessageConsumer consumer1 = session.createConsumer(q1);
         MessageConsumer consumer2 = session.createConsumer(q2);
         MessageConsumer consumer3 = session.createConsumer(q3);

         //each consumer receives one
         assertNotNull(consumer1.receive(2000));
         assertNotNull(consumer2.receive(2000));
         assertNotNull(consumer3.receive(2000));

         Queue queue1 = getProxyToQueue(anycastQ1.toString());
         assertTrue("Message not consumed on Q1", Wait.waitFor(() -> queue1.getMessageCount() == 0));
         Queue queue2 = getProxyToQueue(anycastQ2.toString());
         assertTrue("Message not consumed on Q2", Wait.waitFor(() -> queue2.getMessageCount() == 0));
         Queue queue3 = getProxyToQueue(anycastQ3.toString());
         assertTrue("Message not consumed on Q3", Wait.waitFor(() -> queue3.getMessageCount() == 0));

         connection.close();
         //queues are empty now
         for (SimpleString q : new SimpleString[]{anycastQ1, anycastQ2, anycastQ3}) {
            //FQQN query
            final QueueQueryResult query = server.queueQuery(CompositeAddress.toFullQN(anycastAddress, q));
            assertTrue(query.isExists());
            assertEquals(anycastAddress, query.getAddress());
            assertEquals(CompositeAddress.toFullQN(anycastAddress, q), query.getName());
            assertEquals("Message not consumed", 0, query.getMessageCount());
            //try query again using qName
            QueueQueryResult qNameQuery = server.queueQuery(q);
            assertEquals(q, qNameQuery.getName());
         }
      } finally {
         connection.close();
      }
   }

   @Test
   public void testQueueSpecial() throws Exception {
      server.createQueue(anycastAddress, RoutingType.ANYCAST, anycastQ1, null, true, false, -1, false, true);

      Connection connection = createConnection();
      try {
         connection.start();
         Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

         //::queue ok!
         String specialName = CompositeAddress.toFullQN(new SimpleString(""), anycastQ1).toString();
         javax.jms.Queue q1 = session.createQueue(specialName);

         ClientSessionFactory cf = createSessionFactory(locator);
         ClientSession coreSession = cf.createSession();

         ClientProducer coreProducer = coreSession.createProducer(anycastAddress);
         sendMessages(coreSession, coreProducer, 1);

         System.out.println("create consumer: " + q1);
         MessageConsumer consumer1 = session.createConsumer(q1);

         assertNotNull(consumer1.receive(2000));

         //queue::
         specialName = CompositeAddress.toFullQN(anycastQ1, new SimpleString("")).toString();
         q1 = session.createQueue(specialName);
         try {
            session.createConsumer(q1);
            fail("should get exception");
         } catch (InvalidDestinationException e) {
            //expected
         }

         //::
         specialName = CompositeAddress.toFullQN(new SimpleString(""), new SimpleString("")).toString();
         q1 = session.createQueue(specialName);
         try {
            session.createConsumer(q1);
            fail("should get exception");
         } catch (InvalidDestinationException e) {
            //expected
         }
      } finally {
         connection.close();
      }
   }
}
