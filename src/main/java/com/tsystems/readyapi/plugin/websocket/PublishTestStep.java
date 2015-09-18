package com.tsystems.readyapi.plugin.websocket;

import javax.swing.ImageIcon;

import org.apache.log4j.Logger;

import com.eviware.soapui.SoapUI;
import com.eviware.soapui.config.TestStepConfig;
import com.eviware.soapui.impl.wsdl.support.IconAnimator;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlTestStepResult;
import com.eviware.soapui.model.propertyexpansion.PropertyExpansionContext;
import com.eviware.soapui.model.support.DefaultTestStepProperty;
import com.eviware.soapui.model.support.TestStepBeanProperty;
import com.eviware.soapui.model.testsuite.TestStepResult;
import com.eviware.soapui.monitor.TestMonitor;
import com.eviware.soapui.plugins.auto.PluginTestStep;
import com.eviware.soapui.support.StringUtils;
import com.eviware.soapui.support.UISupport;
import com.eviware.soapui.support.xml.XmlObjectConfigurationReader;

@PluginTestStep(typeName = "websocketPublishTestStep", name = "Publish using Websocket",
        description = "Publishes a specified message through websocket protocol.",
        iconPath = "com/smartbear/assets/publish_step.png")
public class PublishTestStep extends ConnectedTestStep {

    private final static String MESSAGE_KIND_SETTING_NAME = "MessageKind";
    private final static String MESSAGE_SETTING_NAME = "Message";

    private final static String MESSAGE_TYPE_PROP_NAME = "MessageType";
    private final static String MESSAGE_PROP_NAME = "Message";
    private final static Logger log = Logger.getLogger(PluginConfig.LOGGER_NAME);
    public final static PublishedMessageType DEFAULT_MESSAGE_TYPE = PublishedMessageType.Json;

    private PublishedMessageType messageKind = DEFAULT_MESSAGE_TYPE;
    private String message;

    private static boolean actionGroupAdded = false;

    private ImageIcon disabledStepIcon;
    private ImageIcon unknownStepIcon;
    private IconAnimator<PublishTestStep> iconAnimator;

    public PublishTestStep(WsdlTestCase testCase, TestStepConfig config, boolean forLoadTest) {
        super(testCase, config, true, forLoadTest);
        if (!actionGroupAdded) {
            SoapUI.getActionRegistry().addActionGroup(new PublishTestStepActionGroup());
            actionGroupAdded = true;
        }
        if (config != null && config.getConfig() != null) {
            XmlObjectConfigurationReader reader = new XmlObjectConfigurationReader(config.getConfig());
            readData(reader);
        }

        addProperty(new DefaultTestStepProperty(MESSAGE_TYPE_PROP_NAME, false,
                new DefaultTestStepProperty.PropertyHandler() {
                    @Override
                    public String getValue(DefaultTestStepProperty property) {
                        return messageKind.toString();
                    }

                    @Override
                    public void setValue(DefaultTestStepProperty property, String value) {
                        PublishedMessageType messageType = PublishedMessageType.fromString(value);
                        if (messageType != null)
                            setMessageKind(messageType);
                    }
                }, this));
        addProperty(new TestStepBeanProperty(MESSAGE_PROP_NAME, false, this, "message", this));

        addProperty(new DefaultTestStepProperty(TIMEOUT_PROP_NAME, false,
                new DefaultTestStepProperty.PropertyHandler() {
                    @Override
                    public String getValue(DefaultTestStepProperty property) {
                        return Integer.toString(getTimeout());
                    }

                    @Override
                    public void setValue(DefaultTestStepProperty property, String value) {
                        int newTimeout;
                        try {
                            newTimeout = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            return;
                        }
                        setTimeout(newTimeout);
                    }

                }, this));

        if (!forLoadTest)
            initIcons();
        setIcon(unknownStepIcon);
        TestMonitor testMonitor = SoapUI.getTestMonitor();
        if (testMonitor != null)
            testMonitor.addTestMonitorListener(this);
    }

    protected void initIcons() {
        unknownStepIcon = UISupport.createImageIcon("com/smartbear/assets/unknown_publish_step.png");
        disabledStepIcon = UISupport.createImageIcon("com/smartbear/assets/disabled_publish_step.png");

        iconAnimator = new IconAnimator<PublishTestStep>(this, "com/smartbear/assets/unknown_publish_step.png",
                "com/smartbear/assets/publish_step.png", 5);
    }

    @Override
    public void release() {
        TestMonitor testMonitor = SoapUI.getTestMonitor();
        if (testMonitor != null)
            testMonitor.removeTestMonitorListener(this);
        super.release();
    }

    private boolean checkProperties(WsdlTestStepResult result, PublishedMessageType messageTypeToCheck,
            String messageToCheck) {
        boolean ok = true;
        if (messageTypeToCheck == null) {
            result.addMessage("The message format is not specified.");
            ok = false;
        }
        if (StringUtils.isNullOrEmpty(messageToCheck) && messageTypeToCheck != PublishedMessageType.Text) {
            if (messageTypeToCheck == PublishedMessageType.BinaryFile)
                result.addMessage("A file which contains a message is not specified");
            else
                result.addMessage("A message content is not specified.");
            ok = false;
        }

        return ok;
    }

    @Override
    protected ExecutableTestStepResult doExecute(PropertyExpansionContext testRunContext,
            CancellationToken cancellationToken) {

        ExecutableTestStepResult testStepResult = new ExecutableTestStepResult(this);
        testStepResult.startTimer();
        testStepResult.setStatus(TestStepResult.TestStepStatus.OK);
        if (iconAnimator != null)
            iconAnimator.start();
        try {
            try {
                Client client = getClient(testRunContext, testStepResult);
                if (client == null) {
                    testStepResult.setStatus(TestStepResult.TestStepStatus.FAILED);
                    return testStepResult;
                }
                String expandedMessage = testRunContext.expand(message);

                if (!checkProperties(testStepResult, messageKind, expandedMessage)) {
                    testStepResult.setStatus(TestStepResult.TestStepStatus.FAILED);
                    return testStepResult;
                }
                long starTime = System.nanoTime();
                long maxTime = getTimeout() == 0 ? Long.MAX_VALUE : starTime + (long) getTimeout() * 1000 * 1000;

                Message<?> message;
                try {
                    message = messageKind.toMessage(expandedMessage, getOwningProject());
                } catch (RuntimeException e) {
                    testStepResult.addMessage(e.getMessage());
                    testStepResult.setStatus(TestStepResult.TestStepStatus.FAILED);
                    return testStepResult;
                }

                if (!waitForConnection(client, cancellationToken, testStepResult, maxTime))
                    return testStepResult;

                if (!sendMessage(client, message, cancellationToken, testStepResult, maxTime))
                    return testStepResult;

            } catch (Exception e) {
                testStepResult.setStatus(TestStepResult.TestStepStatus.FAILED);
                testStepResult.setError(e);
            }
            return testStepResult;
        } finally {
            testStepResult.stopTimer();
            if (iconAnimator != null)
                iconAnimator.stop();
            testStepResult.setOutcome(formOutcome(testStepResult));
            log.info(String.format("%s - [%s test step]", testStepResult.getOutcome(), getName()));
            notifyExecutionListeners(testStepResult);
        }
    }

    private String formOutcome(WsdlTestStepResult executionResult) {
        switch (executionResult.getStatus()) {
        case CANCELED:
            return "CANCELED";
        case FAILED:
            if (executionResult.getError() == null)
                return "Unable to publish the message (" + StringUtils.join(executionResult.getMessages(), " ") + ")";
            else
                return "Error during message publishing: " + Utils.getExceptionMessage(executionResult.getError());
        default:
            return String.format("The message has been published within %d ms", executionResult.getTimeTaken());

        }

    }

    public PublishedMessageType getMessageKind() {
        return messageKind;
    }

    public void setMessageKind(PublishedMessageType newValue) {
        if (messageKind == newValue)
            return;
        PublishedMessageType old = messageKind;
        messageKind = newValue;
        updateData();
        notifyPropertyChanged("messageKind", old, newValue);
        firePropertyValueChanged(MESSAGE_TYPE_PROP_NAME, old.toString(), newValue.toString());
        String oldMessage = getMessage();
        if (oldMessage == null)
            oldMessage = "";
        try {
            switch (messageKind) {
            case IntegerValue:
                Integer.parseInt(oldMessage);
                break;
            case LongValue:
                Long.parseLong(oldMessage);
                break;
            case FloatValue:
                Float.parseFloat(oldMessage);
                break;
            case DoubleValue:
                Double.parseDouble(oldMessage);
                break;
            }
        } catch (NumberFormatException e) {
            setMessage("0");
        }
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String value) {
        try {
            switch (messageKind) {
            case IntegerValue:
                Integer.parseInt(value);
                break;
            case LongValue:
                Long.parseLong(value);
                break;
            }
        } catch (NumberFormatException e) {
            return;
        }
        setProperty("message", MESSAGE_PROP_NAME, value);
    }

    @Override
    public ExecutableTestStepResult execute(PropertyExpansionContext runContext, CancellationToken cancellationToken) {
        updateState();
        try {
            return doExecute(runContext, cancellationToken);
        } finally {
            cleanAfterExecution(runContext);
        }

    }

    @Override
    protected void readData(XmlObjectConfigurationReader reader) {
        super.readData(reader);
        try {
            messageKind = PublishedMessageType.valueOf(reader.readString(MESSAGE_KIND_SETTING_NAME,
                    DEFAULT_MESSAGE_TYPE.name()));
        } catch (IllegalArgumentException | NullPointerException e) {
            messageKind = DEFAULT_MESSAGE_TYPE;
        }
        message = reader.readString(MESSAGE_SETTING_NAME, "");
    }

    @Override
    protected void writeData(XmlObjectBuilder builder) {
        super.writeData(builder);
        if (messageKind != null)
            builder.add(MESSAGE_KIND_SETTING_NAME, messageKind.name());
        builder.add(MESSAGE_SETTING_NAME, message);
    }

    @Override
    protected void updateState() {
        if (iconAnimator == null)
            return;
        TestMonitor testMonitor = SoapUI.getTestMonitor();
        if (testMonitor != null
                && (testMonitor.hasRunningLoadTest(getTestCase()) || testMonitor.hasRunningSecurityTest(getTestCase())))
            setIcon(disabledStepIcon);
        else
            setIcon(unknownStepIcon);
    }

}