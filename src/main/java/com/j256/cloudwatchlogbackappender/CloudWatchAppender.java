package com.j256.cloudwatchlogbackappender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeTagsRequest;
import com.amazonaws.services.ec2.model.DescribeTagsResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.TagDescription;
import com.amazonaws.services.logs.AWSLogsClient;
import com.amazonaws.services.logs.model.CreateLogGroupRequest;
import com.amazonaws.services.logs.model.CreateLogStreamRequest;
import com.amazonaws.services.logs.model.DataAlreadyAcceptedException;
import com.amazonaws.services.logs.model.DescribeLogGroupsRequest;
import com.amazonaws.services.logs.model.DescribeLogGroupsResult;
import com.amazonaws.services.logs.model.DescribeLogStreamsRequest;
import com.amazonaws.services.logs.model.DescribeLogStreamsResult;
import com.amazonaws.services.logs.model.InputLogEvent;
import com.amazonaws.services.logs.model.InvalidSequenceTokenException;
import com.amazonaws.services.logs.model.LogGroup;
import com.amazonaws.services.logs.model.LogStream;
import com.amazonaws.services.logs.model.PutLogEventsRequest;
import com.amazonaws.services.logs.model.PutLogEventsResult;
import com.amazonaws.util.EC2MetadataUtils;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

/**
 * CloudWatch log appender for logback.
 * 
 * @author graywatson
 */
public class CloudWatchAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

	/** size of batch to write to cloudwatch api */
	private static final int DEFAULT_MAX_BATCH_SIZE = 128;
	/** time in millis to wait until we have a bunch of events to write */
	private static final long DEFAULT_MAX_BATCH_TIME_MILLIS = 5000;
	/** internal event queue size before we drop log requests on the floor */
	private static final int DEFAULT_INTERNAL_QUEUE_SIZE = 8192;
	/** create log destination group and stream when we startup */
	private static final boolean DEFAULT_CREATE_LOG_DESTS = true;
	/** max time to wait in millis before dropping a log event on the floor */
	private static final long DEFAULT_MAX_QUEUE_WAIT_TIME_MILLIS = 100;
	/** initial startup sleep time to wait for the log stuff to configure itself before we log stuff internally */
	private static final long DEFAULT_INITIAL_STARTUP_SLEEP_MILLIS = 2000;
	/** if we don't know the ec2 instance name (or aren't in ec2) then return this */
	private static final String EC2_INSTANCE_UNKNOWN = "unknown";
	private static final String DEFAULT_MESSAGE_PATTERN = "[{instance}] [{thread}] {level} {logger} - {msg}";
	private static final boolean DEFAULT_LOG_EXCEPTIONS = true;

	private String accessKey;
	private String secretKey;
	private String region;
	private String logGroup;
	private String logStream;
	private String messagePattern = DEFAULT_MESSAGE_PATTERN;
	private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
	private long maxBatchTimeMillis = DEFAULT_MAX_BATCH_TIME_MILLIS;
	private long maxQueueWaitTimeMillis = DEFAULT_MAX_QUEUE_WAIT_TIME_MILLIS;
	private long initialStartupSleepMillis = DEFAULT_INITIAL_STARTUP_SLEEP_MILLIS;
	private int internalQueueSize = DEFAULT_INTERNAL_QUEUE_SIZE;
	private boolean createLogDests = DEFAULT_CREATE_LOG_DESTS;
	private boolean logExceptions = DEFAULT_LOG_EXCEPTIONS;

	private AWSLogsClient awsLogsClient;

	private BlockingQueue<ILoggingEvent> loggingEventQueue;
	private Thread cloudWatchWriterThread;
	private final ThreadLocal<Boolean> stopMessagesThreadLocal = new ThreadLocal<Boolean>();
	private StringTemplate messageTemplate;
	private volatile boolean warningMessagePrinted;

	public CloudWatchAppender() {
		// for spring
	}

	/**
	 * After all of the setters, call initial to setup the appender.
	 */
	@Override
	public void start() {
		/*
		 * NOTE: as we startup here, we can't make any log calls so we can't make any RPC calls or anything without
		 * going recursive.
		 */
		if (MiscUtils.isBlank(accessKey)) {
			throw new IllegalStateException("Access-key not set or invalid for appender");
		}
		if (MiscUtils.isBlank(secretKey)) {
			throw new IllegalStateException("Secret-key not set or invalid for appender");
		}
		if (MiscUtils.isBlank(region)) {
			throw new IllegalStateException("Region not set or invalid for appender: " + region);
		}
		if (MiscUtils.isBlank(logGroup)) {
			throw new IllegalStateException("Log group name not set or invalid for appender: " + logGroup);
		}
		if (MiscUtils.isBlank(logStream)) {
			throw new IllegalStateException("Log stream name not set or invalid for appender: " + logStream);
		}

		messageTemplate = new StringTemplate(messagePattern, "{", "}");
		loggingEventQueue = new ArrayBlockingQueue<ILoggingEvent>(internalQueueSize);

		// create our writer thread in the background
		cloudWatchWriterThread = new Thread(new CloudWatchWriter(), getClass().getSimpleName());
		cloudWatchWriterThread.setDaemon(true);
		cloudWatchWriterThread.start();

		super.start();
	}

	@Override
	public void stop() {
		super.stop();

		cloudWatchWriterThread.interrupt();
		try {
			cloudWatchWriterThread.join(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (awsLogsClient != null) {
			awsLogsClient.shutdown();
			awsLogsClient = null;
		}
	}

	@Override
	protected void append(ILoggingEvent loggingEvent) {

		// check wiring
		if (loggingEventQueue == null) {
			if (!warningMessagePrinted) {
				System.err.println(getClass().getSimpleName() + " not wired correctly, ignoring all log messages");
				warningMessagePrinted = true;
			}
			return;
		}

		// skip it if we just went recursive
		Boolean guarded = stopMessagesThreadLocal.get();
		if (guarded == null || !guarded) {
			try {
				if (!loggingEventQueue.offer(loggingEvent, maxQueueWaitTimeMillis, TimeUnit.MILLISECONDS)) {
					// TODO: if this fails, we should log it locally or something
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	// required
	public void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	// required
	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	// required
	public void setRegion(String region) {
		this.region = region;
	}

	// required
	public void setLogGroup(String logGroup) {
		this.logGroup = logGroup;
	}

	// required
	public void setLogStream(String logStream) {
		this.logStream = logStream;
	}

	// not-required, default is DEFAULT_PATTERN
	public void setMessagePattern(String messagePattern) {
		this.messagePattern = messagePattern;
	}

	// not-required, default is DEFAULT_MAX_BATCH_SIZE
	public void setMaxBatchSize(int maxBatchSize) {
		this.maxBatchSize = maxBatchSize;
	}

	// not-required, default is DEFAULT_MAX_BATCH_TIME_MILLIS
	public void setMaxBatchTimeMillis(long maxBatchTimeMillis) {
		this.maxBatchTimeMillis = maxBatchTimeMillis;
	}

	// not-required, default is DEFAULT_MAX_QUEUE_WAIT_TIME_MILLIS
	public void setMaxQueueWaitTimeMillis(long maxQueueWaitTimeMillis) {
		this.maxQueueWaitTimeMillis = maxQueueWaitTimeMillis;
	}

	// not-required, default is DEFAULT_INITIAL_STARTUP_SLEEP_MILLIS
	public void setInitialStartupSleepMillis(long initialStartupSleepMillis) {
		this.initialStartupSleepMillis = initialStartupSleepMillis;
	}

	// not-required, default is DEFAULT_INTERNAL_QUEUE_SIZE
	public void setInternalQueueSize(int internalQueueSize) {
		this.internalQueueSize = internalQueueSize;
	}

	// not-required, default is DEFAULT_CREATE_LOG_DESTS
	public void setCreateLogDests(boolean createLogDests) {
		this.createLogDests = createLogDests;
	}

	// not-required, default is DEFAULT_LOG_EXCEPTIONS
	public void setLogExceptions(boolean logExceptions) {
		this.logExceptions = logExceptions;
	}

	/**
	 * Background thread that writes the log events to cloudwatch.
	 */
	private class CloudWatchWriter implements Runnable {

		private String sequenceToken;
		private String instanceName;
		private final StringBuilder sb = new StringBuilder(1024);
		private final Map<String, Object> templateMap = new HashMap<String, Object>();

		@Override
		public void run() {

			try {
				/*
				 * We sleep here because we want the various log subsystems to get wired up correctly before we start
				 * making requests which can cause recursion.
				 */
				Thread.sleep(initialStartupSleepMillis);
			} catch (InterruptedException e) {
				// ignore
			}

			AWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);
			awsLogsClient = new AWSLogsClient(awsCredentials);
			awsLogsClient.setRegion(RegionUtils.getRegion(region));
			verifyLogGroupExists();
			verifLogStreamExists();
			instanceName = requestInstanceName(awsCredentials);
			addInfo(getClass().getSimpleName() + " started");

			List<ILoggingEvent> events = new ArrayList<ILoggingEvent>(maxBatchSize);
			Thread thread = Thread.currentThread();
			while (!thread.isInterrupted()) {
				long batchTimeout = System.currentTimeMillis() + maxBatchTimeMillis;
				while (!thread.isInterrupted()) {
					long timeoutMillis = batchTimeout - System.currentTimeMillis();
					if (timeoutMillis < 0) {
						break;
					}
					ILoggingEvent loggingEvent;
					try {
						loggingEvent = loggingEventQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						writeEvents(events);
						return;
					}
					if (loggingEvent == null) {
						// wait timed out
						break;
					}
					events.add(loggingEvent);
					if (events.size() >= maxBatchSize) {
						// batch size exceeded
						break;
					}
				}
				if (!events.isEmpty()) {
					writeEvents(events);
					events.clear();
				}
			}

			// now clear the queue and write all the rest
			events.clear();
			while (true) {
				ILoggingEvent event = loggingEventQueue.poll();
				if (event == null) {
					// nothing else waiting
					break;
				}
				events.add(event);
				if (events.size() >= maxBatchSize) {
					writeEvents(events);
					events.clear();
				}
			}
		}

		private void verifyLogGroupExists() {
			DescribeLogGroupsRequest request = new DescribeLogGroupsRequest().withLogGroupNamePrefix(logGroup);
			sequenceToken = request.getNextToken();
			DescribeLogGroupsResult result = awsLogsClient.describeLogGroups(request);
			for (LogGroup group : result.getLogGroups()) {
				if (logGroup.equals(group.getLogGroupName())) {
					return;
				}
			}
			if (createLogDests) {
				CreateLogGroupRequest createRequest = new CreateLogGroupRequest(logGroup);
				awsLogsClient.createLogGroup(createRequest);
				addInfo("Created log-group '" + logGroup + "'");
			} else {
				addWarn("Log-group '" + logGroup + "' doesn't exist and not created");
			}
		}

		private void verifLogStreamExists() {
			DescribeLogStreamsRequest request =
					new DescribeLogStreamsRequest().withLogGroupName(logGroup).withLogStreamNamePrefix(logStream);
			DescribeLogStreamsResult result = awsLogsClient.describeLogStreams(request);
			for (LogStream stream : result.getLogStreams()) {
				if (logStream.equals(stream.getLogStreamName())) {
					sequenceToken = stream.getUploadSequenceToken();
					return;
				}
			}
			if (createLogDests) {
				CreateLogStreamRequest createRequest = new CreateLogStreamRequest(logGroup, logStream);
				awsLogsClient.createLogStream(createRequest);
				addInfo("Created log-stream '" + logStream + "' for group '" + logGroup + "'");
			} else {
				addWarn("Log-stream '" + logStream + "' doesn't exist and not created");
			}
		}

		private void writeEvents(List<ILoggingEvent> events) {
			// we need this in case our RPC calls create log output which we don't want to then log again
			stopMessagesThreadLocal.set(true);
			Exception exception = null;
			try {
				List<InputLogEvent> logEvents = new ArrayList<InputLogEvent>(events.size());
				for (ILoggingEvent event : events) {
					InputLogEvent logEvent =
							new InputLogEvent().withTimestamp(event.getTimeStamp()).withMessage(eventToString(event));
					logEvents.add(logEvent);
				}

				PutLogEventsRequest request =
						new PutLogEventsRequest(logGroup, logStream, logEvents).withSequenceToken(sequenceToken);
				PutLogEventsResult result = awsLogsClient.putLogEvents(request);
				sequenceToken = result.getNextSequenceToken();
			} catch (DataAlreadyAcceptedException daac) {
				exception = daac;
				sequenceToken = daac.getExpectedSequenceToken();
			} catch (InvalidSequenceTokenException iste) {
				exception = iste;
				sequenceToken = iste.getExpectedSequenceToken();
			} catch (Exception e) {
				// catch everything else to make sure we don't quit the thread
				exception = e;
			} finally {
				stopMessagesThreadLocal.set(false);
				if (exception != null) {
					addError("Exception thrown when creating logging " + events.size() + " events", exception);
				}
			}
		}

		private String requestInstanceName(AWSCredentials awsCredentials) {
			String instanceId = EC2MetadataUtils.getInstanceId();
			if (instanceId == null) {
				return EC2_INSTANCE_UNKNOWN;
			}
			try {
				AmazonEC2Client ec2Client = new AmazonEC2Client(awsCredentials);
				DescribeTagsRequest request = new DescribeTagsRequest();
				request.setFilters(Arrays.asList(new Filter("resource-type").withValues("instance"),
						new Filter("resource-id").withValues(instanceId)));
				DescribeTagsResult result = ec2Client.describeTags(request);
				List<TagDescription> tags = result.getTags();
				for (TagDescription tag : tags) {
					if ("Name".equals(tag.getKey())) {
						return tag.getValue();
					}
				}
				addInfo("Could not find EC2 instance name in tags: " + tags);
			} catch (AmazonServiceException ase) {
				addInfo("Looking up EC2 instance-name threw: " + ase);
			}
			return EC2_INSTANCE_UNKNOWN;
		}

		private String eventToString(ILoggingEvent loggingEvent) {
			sb.setLength(0);
			templateMap.clear();
			templateMap.put("instance", instanceName);
			templateMap.put("thread", loggingEvent.getThreadName());
			templateMap.put("level", loggingEvent.getLevel());
			templateMap.put("logger", loggingEvent.getLoggerName());
			templateMap.put("msg", loggingEvent.getFormattedMessage());
			messageTemplate.render(templateMap, sb);
			// handle any throw information
			if (logExceptions && loggingEvent.getThrowableProxy() != null) {
				sb.append('\n');
				boolean first = true;
				sb.setLength(0);
				for (IThrowableProxy throwable = loggingEvent.getThrowableProxy(); throwable != null; throwable =
						throwable.getCause()) {
					if (first) {
						first = false;
					} else {
						sb.append("Caused by: ");
					}
					sb.append(throwable.getClassName()).append(": ").append(throwable.getMessage()).append("\n");
					for (StackTraceElementProxy proxy : throwable.getStackTraceElementProxyArray()) {
						sb.append("     ").append(proxy.getSTEAsString()).append("\n");
					}
				}
			}
			return sb.toString();
		}
	}
}