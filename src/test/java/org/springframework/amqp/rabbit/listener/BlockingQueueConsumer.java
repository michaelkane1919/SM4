/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.springframework.amqp.rabbit.listener;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.*;
import com.rabbitmq.utility.Utility;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.exception.ConsumerCancelledException;
import org.springframework.amqp.rabbit.listener.exception.FatalListenerStartupException;
import org.springframework.amqp.rabbit.support.MessagePropertiesConverter;
import org.springframework.amqp.rabbit.support.RabbitExceptionTranslator;
import org.springframework.amqp.support.ConsumerTagStrategy;
import org.springframework.util.backoff.BackOffExecution;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Specialized consumer encapsulating knowledge of the broker
 * connections and having its own lifecycle (start and stop).
 *
 * @author Mark Pollack
 * @author Dave Syer
 * @author Gary Russell
 * @author Casper Mout
 * @author Artem Bilan
 * @author Alex Panchenko
 */
public class BlockingQueueConsumer {

    private static Log logger = LogFactory.getLog(BlockingQueueConsumer.class);

    private final BlockingQueue<Delivery> queue;
    private final String[] queues;
    private final int prefetchCount;
    private final boolean transactional;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean cancelReceived = new AtomicBoolean(false);
    private final AcknowledgeMode acknowledgeMode;
    private final ConnectionFactory connectionFactory;
    private final MessagePropertiesConverter messagePropertiesConverter;
    private final ActiveObjectCounter<BlockingQueueConsumer> activeObjectCounter;
    private final Map<String, Object> consumerArgs = new HashMap<String, Object>();
    private final boolean exclusive;
    private final Set<Long> deliveryTags = new LinkedHashSet<Long>();
    private final boolean defaultRequeuRejected;
    private final Map<String, String> consumerTags = new ConcurrentHashMap<String, String>();
    private final Set<String> missingQueues = Collections.synchronizedSet(new HashSet<String>());
    // When this is non-null the connection has been closed (should never happen in normal operation).
    private volatile ShutdownSignalException shutdown;
    private Channel channel;
    private RabbitResourceHolder resourceHolder;
    private InternalConsumer consumer;
    private long retryDeclarationInterval = 60000;

    private long failedDeclarationRetryInterval = 5000;

    private int declarationRetries = 3;

    private long lastRetryDeclaration;

    private ConsumerTagStrategy tagStrategy;

    private BackOffExecution backOffExecution;

    /**
     * Create a consumer. The consumer must not attempt to use
     * the connection factory or communicate with the broker
     * until it is started. RequeueRejected defaults to true.
     *
     * @param connectionFactory          The connection factory.
     * @param messagePropertiesConverter The properties converter.
     * @param activeObjectCounter        The active object counter; used during shutdown.
     * @param acknowledgeMode            The acknowledgemode.
     * @param transactional              Whether the channel is transactional.
     * @param prefetchCount              The prefetch count.
     * @param queues                     The queues.
     */
    public BlockingQueueConsumer(ConnectionFactory connectionFactory, MessagePropertiesConverter messagePropertiesConverter, ActiveObjectCounter<BlockingQueueConsumer> activeObjectCounter, AcknowledgeMode acknowledgeMode, boolean transactional, int prefetchCount, String... queues) {
        this(connectionFactory, messagePropertiesConverter, activeObjectCounter, acknowledgeMode, transactional, prefetchCount, true, queues);
    }

    /**
     * Create a consumer. The consumer must not attempt to use
     * the connection factory or communicate with the broker
     * until it is started.
     *
     * @param connectionFactory          The connection factory.
     * @param messagePropertiesConverter The properties converter.
     * @param activeObjectCounter        The active object counter; used during shutdown.
     * @param acknowledgeMode            The acknowledge mode.
     * @param transactional              Whether the channel is transactional.
     * @param prefetchCount              The prefetch count.
     * @param defaultRequeueRejected     true to reject requeued messages.
     * @param queues                     The queues.
     */
    public BlockingQueueConsumer(ConnectionFactory connectionFactory, MessagePropertiesConverter messagePropertiesConverter, ActiveObjectCounter<BlockingQueueConsumer> activeObjectCounter, AcknowledgeMode acknowledgeMode, boolean transactional, int prefetchCount, boolean defaultRequeueRejected, String... queues) {
        this(connectionFactory, messagePropertiesConverter, activeObjectCounter, acknowledgeMode, transactional, prefetchCount, defaultRequeueRejected, null, queues);
    }

    /**
     * Create a consumer. The consumer must not attempt to use the
     * connection factory or communicate with the broker
     * until it is started.
     *
     * @param connectionFactory          The connection factory.
     * @param messagePropertiesConverter The properties converter.
     * @param activeObjectCounter        The active object counter; used during shutdown.
     * @param acknowledgeMode            The acknowledge mode.
     * @param transactional              Whether the channel is transactional.
     * @param prefetchCount              The prefetch count.
     * @param defaultRequeueRejected     true to reject requeued messages.
     * @param consumerArgs               The consumer arguments (e.g. x-priority).
     * @param queues                     The queues.
     */
    public BlockingQueueConsumer(ConnectionFactory connectionFactory, MessagePropertiesConverter messagePropertiesConverter, ActiveObjectCounter<BlockingQueueConsumer> activeObjectCounter, AcknowledgeMode acknowledgeMode, boolean transactional, int prefetchCount, boolean defaultRequeueRejected, Map<String, Object> consumerArgs, String... queues) {
        this(connectionFactory, messagePropertiesConverter, activeObjectCounter, acknowledgeMode, transactional, prefetchCount, defaultRequeueRejected, consumerArgs, false, queues);
    }

    /**
     * Create a consumer. The consumer must not attempt to use
     * the connection factory or communicate with the broker
     * until it is started.
     *
     * @param connectionFactory          The connection factory.
     * @param messagePropertiesConverter The properties converter.
     * @param activeObjectCounter        The active object counter; used during shutdown.
     * @param acknowledgeMode            The acknowledge mode.
     * @param transactional              Whether the channel is transactional.
     * @param prefetchCount              The prefetch count.
     * @param defaultRequeueRejected     true to reject requeued messages.
     * @param consumerArgs               The consumer arguments (e.g. x-priority).
     * @param exclusive                  true if the consumer is to be exclusive.
     * @param queues                     The queues.
     */
    public BlockingQueueConsumer(ConnectionFactory connectionFactory, MessagePropertiesConverter messagePropertiesConverter, ActiveObjectCounter<BlockingQueueConsumer> activeObjectCounter, AcknowledgeMode acknowledgeMode, boolean transactional, int prefetchCount, boolean defaultRequeueRejected, Map<String, Object> consumerArgs, boolean exclusive, String... queues) {
        this.connectionFactory = connectionFactory;
        this.messagePropertiesConverter = messagePropertiesConverter;
        this.activeObjectCounter = activeObjectCounter;
        this.acknowledgeMode = acknowledgeMode;
        this.transactional = transactional;
        this.prefetchCount = prefetchCount;
        this.defaultRequeuRejected = defaultRequeueRejected;
        if (consumerArgs != null && consumerArgs.size() > 0) {
            this.consumerArgs.putAll(consumerArgs);
        }
        this.exclusive = exclusive;
        this.queues = queues;
        this.queue = new LinkedBlockingQueue<Delivery>(prefetchCount);
    }

    public Channel getChannel() {
        return channel;
    }

    public String getConsumerTag() {
        return consumer.getConsumerTag();
    }

    /**
     * Stop receiving new messages; drain the queue of any prefetched messages.
     *
     * @param shutdownTimeout how long (ms) to suspend the client thread.
     * @deprecated as redundant option in favor of {@link #basicCancel}.
     */
    @Deprecated
    public final void setQuiesce(long shutdownTimeout) {
    }

    /**
     * Set the number of retries after passive queue declaration fails.
     *
     * @param declarationRetries The number of retries, default 3.
     * @see #setFailedDeclarationRetryInterval(long)
     * @since 1.3.9
     */
    public void setDeclarationRetries(int declarationRetries) {
        this.declarationRetries = declarationRetries;
    }

    /**
     * Set the interval between passive queue declaration attempts in milliseconds.
     *
     * @param failedDeclarationRetryInterval the interval, default 5000.
     * @see #setDeclarationRetries(int)
     * @since 1.3.9
     */
    public void setFailedDeclarationRetryInterval(long failedDeclarationRetryInterval) {
        this.failedDeclarationRetryInterval = failedDeclarationRetryInterval;
    }

    /**
     * When consuming multiple queues, set the interval between declaration attempts when only
     * a subset of the queues were available (milliseconds).
     *
     * @param retryDeclarationInterval the interval, default 60000.
     * @since 1.3.9
     */
    public void setRetryDeclarationInterval(long retryDeclarationInterval) {
        this.retryDeclarationInterval = retryDeclarationInterval;
    }

    /**
     * Set the {@link ConsumerTagStrategy} to use when generating consumer tags.
     *
     * @param tagStrategy the tagStrategy to set
     * @since 1.4.5
     */
    public void setTagStrategy(ConsumerTagStrategy tagStrategy) {
        this.tagStrategy = tagStrategy;
    }

    public BackOffExecution getBackOffExecution() {
        return backOffExecution;
    }

    /**
     * Set the {@link BackOffExecution} to use for the recovery in the {@code SimpleMessageListenerContainer}.
     *
     * @param backOffExecution the backOffExecution.
     * @since 1.5
     */
    public void setBackOffExecution(BackOffExecution backOffExecution) {
        this.backOffExecution = backOffExecution;
    }

    protected void basicCancel() {
        for (String consumerTag : this.consumerTags.keySet()) {
            try {
                this.channel.basicCancel(consumerTag);
            } catch (IOException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Error performing 'basicCancel'", e);
                }
            } catch (AlreadyClosedException e) {
                if (logger.isTraceEnabled()) {
                    logger.trace(this.channel + " is already closed");
                }
                break;
            }
        }
        this.consumerTags.clear();
        this.cancelled.set(true);
    }

    protected boolean hasDelivery() {
        return !this.queue.isEmpty();
    }

    /**
     * Check if we are in shutdown mode and if so throw an exception.
     */
    private void checkShutdown() {
        if (shutdown != null) {
            throw Utility.fixStackTrace(shutdown);
        }
    }

    /**
     * If this is a non-POISON non-null delivery simply return it.
     * If this is POISON we are in shutdown mode, throw
     * shutdown. If delivery is null, we may be in shutdown mode. Check and see.
     *
     * @throws InterruptedException
     */
    private Message handle(Delivery delivery) throws InterruptedException {
        if ((delivery == null && shutdown != null)) {
            throw shutdown;
        }
        if (delivery == null) {
            return null;
        }
        byte[] body = delivery.getBody();
        Envelope envelope = delivery.getEnvelope();

        MessageProperties messageProperties = this.messagePropertiesConverter.toMessageProperties(delivery.getProperties(), envelope, "UTF-8");
        messageProperties.setMessageCount(0);
        messageProperties.setConsumerTag(delivery.getConsumerTag());
        messageProperties.setConsumerQueue(this.consumerTags.get(delivery.getConsumerTag()));
        Message message = new Message(body, messageProperties);
        if (logger.isDebugEnabled()) {
            logger.debug("Received message: " + message);
        }
        deliveryTags.add(messageProperties.getDeliveryTag());
        return message;
    }

    /**
     * Main application-side API: wait for the next message delivery and return it.
     *
     * @return the next message
     * @throws InterruptedException    if an interrupt is received while waiting
     * @throws ShutdownSignalException if the connection is shut down while waiting
     */
    public Message nextMessage() throws InterruptedException, ShutdownSignalException {
        logger.trace("Retrieving delivery for " + this);
        return handle(queue.take());
    }

    /**
     * Main application-side API: wait for the next message delivery and return it.
     *
     * @param timeout timeout in millisecond
     * @return the next message or null if timed out
     * @throws InterruptedException    if an interrupt is received while waiting
     * @throws ShutdownSignalException if the connection is shut down while waiting
     */
    public Message nextMessage(long timeout) throws InterruptedException, ShutdownSignalException {
        if (logger.isDebugEnabled()) {
            logger.debug("Retrieving delivery for " + this);
        }
        checkShutdown();
        if (this.missingQueues.size() > 0) {
            checkMissingQueues();
        }
        Message message = handle(queue.poll(timeout, TimeUnit.MILLISECONDS));
        if (message == null && cancelReceived.get()) {
            throw new ConsumerCancelledException();
        }
        return message;
    }

    /*
     * Check to see if missing queues are now available; use a separate channel so the main
     * channel is not closed by the broker if the declaration fails.
     */
    private void checkMissingQueues() {
        long now = System.currentTimeMillis();
        if (now - this.retryDeclarationInterval > this.lastRetryDeclaration) {
            synchronized (this.missingQueues) {
                Iterator<String> iterator = this.missingQueues.iterator();
                while (iterator.hasNext()) {
                    boolean available = true;
                    String queue = iterator.next();
                    Channel channel = null;
                    try {
                        channel = this.connectionFactory.createConnection().createChannel(false);
                        channel.queueDeclarePassive(queue);
                        if (logger.isInfoEnabled()) {
                            logger.info("Queue '" + queue + "' is now available");
                        }
                    } catch (IOException e) {
                        available = false;
                        if (logger.isWarnEnabled()) {
                            logger.warn("Queue '" + queue + "' is still not available");
                        }
                    } finally {
                        if (channel != null) {
                            try {
                                channel.close();
                            } catch (IOException e) {
                                //Ignore it
                            } catch (TimeoutException e) {
                                //Ignore it
                            }
                        }
                    }
                    if (available) {
                        try {
                            this.consumeFromQueue(queue);
                            iterator.remove();
                        } catch (IOException e) {
                            throw RabbitExceptionTranslator.convertRabbitAccessException(e);
                        }
                    }
                }
            }
            this.lastRetryDeclaration = now;
        }
    }

    public void start() throws AmqpException {
        if (logger.isDebugEnabled()) {
            logger.debug("Starting consumer " + this);
        }
        System.out.println("###eeeeeeBlockingQueueConsumer.start");
        try {

            this.resourceHolder = ConnectionFactoryUtils.getTransactionalResourceHolder(connectionFactory, transactional);

            this.channel = resourceHolder.getChannel();

        } catch (AmqpAuthenticationException e) {
            throw new FatalListenerStartupException("Authentication failure", e);
        }

        this.consumer = new InternalConsumer(channel);
        this.deliveryTags.clear();
        this.activeObjectCounter.add(this);

        // mirrored queue might be being moved
        int passiveDeclareRetries = this.declarationRetries;
        System.out.println("##passiveDeclareRetries==" + passiveDeclareRetries);
        do {
            try {
                attemptPassiveDeclarations();
                if (passiveDeclareRetries < this.declarationRetries && logger.isInfoEnabled()) {
                    logger.info("Queue declaration succeeded after retrying");
                }
                passiveDeclareRetries = 0;
            } catch (DeclarationException e) {
                if (passiveDeclareRetries > 0 && channel.isOpen()) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Queue declaration failed; retries left=" + (passiveDeclareRetries), e);
                        try {
                            Thread.sleep(this.failedDeclarationRetryInterval);
                        } catch (InterruptedException e1) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } else if (e.getFailedQueues().size() < this.queues.length) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Not all queues are available; only listening on those that are - configured: " + Arrays.asList(this.queues) + "; not available: " + e.getFailedQueues());
                    }
                    this.missingQueues.addAll(e.getFailedQueues());
                    this.lastRetryDeclaration = System.currentTimeMillis();
                } else {
                    this.activeObjectCounter.release(this);
                    throw new QueuesNotAvailableException("Cannot prepare queue for listener. " + "Either the queue doesn't exist or the broker will not allow us to use it.", e);
                }
            }

        } while (passiveDeclareRetries-- > 0);

        System.out.println("#acknowledgeMode.isAutoAck()==" + acknowledgeMode.isAutoAck());
        if (!acknowledgeMode.isAutoAck()) {
            // Set basicQos before calling basicConsume (otherwise if we are not acking the broker
            // will send blocks of 100 messages)
            try {
                channel.basicQos(prefetchCount);
            } catch (IOException e) {
                this.activeObjectCounter.release(this);
                throw new AmqpIOException(e);
            }
        }


        try {
            for (String queueName : queues) {
                if (!this.missingQueues.contains(queueName)) {
                    consumeFromQueue(queueName);
                }
            }
        } catch (IOException e) {
            throw RabbitExceptionTranslator.convertRabbitAccessException(e);
        }
    }

    private void consumeFromQueue(String queue) throws IOException {
        String consumerTag = this.channel.basicConsume(queue, this.acknowledgeMode.isAutoAck(), (this.tagStrategy != null ? this.tagStrategy.createConsumerTag(queue) : ""), false, this.exclusive, this.consumerArgs, this.consumer);
        if (consumerTag != null) {
            this.consumerTags.put(consumerTag, queue);
            if (logger.isDebugEnabled()) {
                logger.debug("Started on queue '" + queue + "' with tag " + consumerTag + ": " + this);
            }
        } else {
            logger.error("Null consumer tag received for queue " + queue);
        }
    }

    private void attemptPassiveDeclarations() {
        DeclarationException failures = null;
        for (String queueName : this.queues) {
            try {
                try {
                    this.channel.queueDeclarePassive(queueName);
                } catch (IllegalArgumentException e) {
                    try {
                        if (this.channel instanceof ChannelProxy) {
                            ((ChannelProxy) this.channel).getTargetChannel().close();
                        }
                    } catch (TimeoutException e1) {
                    }
                    throw new FatalListenerStartupException("Illegal Argument on Queue Declaration", e);
                }
            } catch (IOException e) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to declare queue:" + queueName);
                }
                if (!this.channel.isOpen()) {
                    throw new AmqpIOException(e);
                }
                if (failures == null) {
                    failures = new DeclarationException(e);
                }
                failures.addFailedQueue(queueName);
            }
        }
        if (failures != null) {
            throw failures;
        }
    }

    public void stop() {
        this.cancelled.set(true);
        if (consumer != null && consumer.getChannel() != null && this.consumerTags.size() > 0 && !this.cancelReceived.get()) {
            try {
                RabbitUtils.closeMessageConsumer(this.consumer.getChannel(), this.consumerTags.keySet(), this.transactional);
            } catch (Exception e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Error closing consumer", e);
                }
            }
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Closing Rabbit Channel: " + channel);
        }
        RabbitUtils.setPhysicalCloseRequired(true);
        ConnectionFactoryUtils.releaseResources(this.resourceHolder);
        deliveryTags.clear();
        consumer = null;
    }

    @Override
    public String toString() {
        return "Consumer: tags=[" + (this.consumerTags.toString()) + "], channel=" + channel + ", acknowledgeMode=" + acknowledgeMode + " local queue size=" + queue.size();
    }

    /**
     * Perform a rollback, handling rollback exceptions properly.
     *
     * @param ex the thrown application exception or error
     * @throws Exception in case of a rollback error
     */
    public void rollbackOnExceptionIfNecessary(Throwable ex) throws Exception {

        boolean ackRequired = !acknowledgeMode.isAutoAck() && !acknowledgeMode.isManual();
        try {
            if (transactional) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Initiating transaction rollback on application exception: " + ex);
                }
                RabbitUtils.rollbackIfNecessary(channel);
            }
            if (ackRequired) {
                // We should always requeue if the container was stopping
                boolean shouldRequeue = this.defaultRequeuRejected || ex instanceof MessageRejectedWhileStoppingException;
                Throwable t = ex;
                while (shouldRequeue && t != null) {
                    if (t instanceof AmqpRejectAndDontRequeueException) {
                        shouldRequeue = false;
                    }
                    t = t.getCause();
                }
                if (logger.isDebugEnabled()) {
                    logger.debug("Rejecting messages (requeue=" + shouldRequeue + ")");
                }
                for (Long deliveryTag : deliveryTags) {
                    // With newer RabbitMQ brokers could use basicNack here...
                    channel.basicReject(deliveryTag, shouldRequeue);
                }
                if (transactional) {
                    // Need to commit the reject (=nack)
                    RabbitUtils.commitIfNecessary(channel);
                }
            }
        } catch (Exception e) {
            logger.error("Application exception overridden by rollback exception", ex);
            throw e;
        } finally {
            deliveryTags.clear();
        }
    }

    /**
     * Perform a commit or message acknowledgement, as appropriate.
     *
     * @param locallyTransacted Whether the channel is locally transacted.
     * @return true if at least one delivery tag exists.
     * @throws IOException Any IOException.
     */
    public boolean commitIfNecessary(boolean locallyTransacted) throws IOException {

        if (deliveryTags.isEmpty()) {
            return false;
        }

        try {

            boolean ackRequired = !acknowledgeMode.isAutoAck() && !acknowledgeMode.isManual();

            if (ackRequired) {

                if (transactional && !locallyTransacted) {

                    // Not locally transacted but it is transacted so it
                    // could be synchronized with an external transaction
                    for (Long deliveryTag : deliveryTags) {
                        ConnectionFactoryUtils.registerDeliveryTag(connectionFactory, channel, deliveryTag);
                    }

                } else {
                    long deliveryTag = new ArrayList<Long>(deliveryTags).get(deliveryTags.size() - 1);
                    channel.basicAck(deliveryTag, true);
                }
            }

            if (locallyTransacted) {
                // For manual acks we still need to commit
                RabbitUtils.commitIfNecessary(channel);
            }

        } finally {
            deliveryTags.clear();
        }

        return true;

    }

    /**
     * Encapsulates an arbitrary message - simple "bean" holder structure.
     */
    private static class Delivery {

        private final String consumerTag;

        private final Envelope envelope;

        private final BasicProperties properties;

        private final byte[] body;

        public Delivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body) {//NOSONAR
            this.consumerTag = consumerTag;
            this.envelope = envelope;
            this.properties = properties;
            this.body = body;
        }

        public String getConsumerTag() {
            return consumerTag;
        }

        public Envelope getEnvelope() {
            return envelope;
        }

        public BasicProperties getProperties() {
            return properties;
        }

        public byte[] getBody() {
            return body;
        }
    }

    @SuppressWarnings("serial")
    private static class DeclarationException extends AmqpException {

        private final List<String> failedQueues = new ArrayList<String>();

        public DeclarationException() {
            super("Failed to declare queue(s):");
        }

        public DeclarationException(Throwable t) {
            super("Failed to declare queue(s):", t);
        }

        void addFailedQueue(String queue) {
            this.failedQueues.add(queue);
        }

        public List<String> getFailedQueues() {
            return this.failedQueues;
        }

        @Override
        public String getMessage() {
            return super.getMessage() + this.failedQueues.toString();
        }

    }

    private class InternalConsumer extends DefaultConsumer {

        public InternalConsumer(Channel channel) {
            super(channel);
        }

        @Override
        public void handleConsumeOk(String consumerTag) {
            super.handleConsumeOk(consumerTag);
            if (logger.isDebugEnabled()) {
                logger.debug("ConsumeOK : " + BlockingQueueConsumer.this);
            }
        }

        @Override
        public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
            if (logger.isDebugEnabled()) {
                if (RabbitUtils.isNormalShutdown(sig)) {
                    logger.debug("Received shutdown signal for consumer tag=" + consumerTag + ": " + sig.getMessage());
                } else {
                    logger.debug("Received shutdown signal for consumer tag=" + consumerTag, sig);
                }
            }
            shutdown = sig;
            // The delivery tags will be invalid if the channel shuts down
            deliveryTags.clear();
            activeObjectCounter.release(BlockingQueueConsumer.this);
        }

        @Override
        public void handleCancel(String consumerTag) throws IOException {
            if (logger.isWarnEnabled()) {
                logger.warn("Cancel received for " + consumerTag + "; " + BlockingQueueConsumer.this);
            }
            BlockingQueueConsumer.this.consumerTags.remove(consumerTag);
            BlockingQueueConsumer.this.cancelReceived.set(true);
        }

        @Override
        public void handleCancelOk(String consumerTag) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received cancellation notice for tag " + consumerTag + "; " + BlockingQueueConsumer.this);
            }
            synchronized (BlockingQueueConsumer.this.consumerTags) {
                BlockingQueueConsumer.this.consumerTags.remove(consumerTag);
            }
        }

        @Override
        public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body) throws IOException {
            if (logger.isDebugEnabled()) {
                logger.debug("Storing delivery for " + BlockingQueueConsumer.this);
            }
            try {
                queue.put(new Delivery(consumerTag, envelope, properties, body));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

    }

}