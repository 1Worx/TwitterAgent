import org.joda.time.DateTime;
import org.slf4j.Logger;

import com.thingworx.data.util.InfoTableInstanceFactory;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.annotations.ThingworxBaseTemplateDefinition;
import com.thingworx.metadata.annotations.ThingworxEventDefinition;
import com.thingworx.metadata.annotations.ThingworxEventDefinitions;
import com.thingworx.metadata.annotations.ThingworxPropertyDefinition;
import com.thingworx.metadata.annotations.ThingworxPropertyDefinitions;
import com.thingworx.metadata.annotations.ThingworxServiceDefinition;
import com.thingworx.metadata.annotations.ThingworxServiceParameter;
import com.thingworx.metadata.annotations.ThingworxServiceResult;
import com.thingworx.things.Thing;
import com.thingworx.things.events.ThingworxEvent;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.StringPrimitive;
import com.thingworx.webservices.context.ThreadLocalContext;

import twitter4j.ConnectionLifeCycleListener;
import twitter4j.FilterQuery;
import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.StatusListener;
import twitter4j.Twitter;
import twitter4j.TwitterFactory;
import twitter4j.TwitterStream;
import twitter4j.TwitterStreamFactory;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;

@SuppressWarnings("serial")
@ThingworxPropertyDefinitions(properties={
		@ThingworxPropertyDefinition(name="searchString", description="searchString", baseType="STRING", category="Status", aspects={"isReadOnly:false", "defaultValue:#123test"}), 
		@ThingworxPropertyDefinition(name="token", description="token", baseType="STRING", category="Status", aspects={"isReadOnly:false"}),
		@ThingworxPropertyDefinition(name="tokenSecret", description="tokenSecret", baseType="STRING", category="Status", aspects={"isReadOnly:false"}),
		@ThingworxPropertyDefinition(name="consumer", description="consumer", baseType="STRING", category="Status", aspects={"isReadOnly:false"}),
		@ThingworxPropertyDefinition(name="consumerSecret", description="consumerSecret", baseType="STRING", category="Status", aspects={"isReadOnly:false"})
		})
@ThingworxEventDefinitions(events={@ThingworxEventDefinition(name="TweetRecieved", description="Twitter Recieved event", dataShape="TwitterRecievedEvent"), @ThingworxEventDefinition(name = "TweetRecieved", description = "Tweet Recieved Event", category = "", dataShape = "TwitterRecievedEventDataShape", isInvocable = true, isPropertyEvent = false, isLocalOnly = false, aspects = {})})

@ThingworxBaseTemplateDefinition(name = "GenericThing")
public class TwitterStreamTemplate
extends Thing implements ConnectionLifeCycleListener {
	boolean connected = false;
    TwitterStream twitterStream;
    Twitter twitter;
    String searchString = "#123test";
    protected static Logger _logger = LogUtilities.getInstance().getApplicationLogger((Class<?>)TwitterStreamTemplate.class);
    String token = "";
    String tokenSecret = "";
    String consumer = "";
    String consumerSecret = "";
    String text = "1234 not set yet";
    String username = "null";
    boolean initialized = false;
    
    protected void initializeThing() throws Exception {
        super.initializeThing();
        this.searchString = this.getPropertyValue("searchString").getStringValue();
    }

    void connectTwitter() {
    	try {
    		token = this.getPropertyValue("token").getStringValue().trim();
    		tokenSecret = this.getPropertyValue("tokenSecret").getStringValue().trim();
    		consumer = this.getPropertyValue("consumer").getStringValue().trim();
    		consumerSecret = this.getPropertyValue("consumerSecret").getStringValue().trim();
    	} catch(Exception e) {
    		e.printStackTrace();
    	}
    	
        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.setDebugEnabled(true).setOAuthConsumerKey(this.consumer).
        setOAuthConsumerSecret(this.consumerSecret).setOAuthAccessToken(this.token).
        setOAuthAccessTokenSecret(this.tokenSecret).
        setAsyncNumThreads(1).setUserStreamRepliesAllEnabled(false).setPrettyDebugEnabled(true).
        setHttpRetryIntervalSeconds(61).setHttpRetryCount(3);
        Configuration conf = cb.build();
        this.twitterStream = new TwitterStreamFactory(conf).getInstance();
        this.twitterStream.addConnectionLifeCycleListener(this);
        
        StatusListener listener = new StatusListener(){

            public void onStatus(Status status) {
                
            	String text = status.getText();
                //prevent retweets
                try {
                    if (!text.startsWith("RT:") &&
                    		!text.contains(" RT:") &&
                    		text.contains(TwitterStreamTemplate.this.searchString) &&
                    		status.getInReplyToStatusId() == -1) 
                    {

                    	TwitterStreamTemplate.this.text = text;
                    	TwitterStreamTemplate.this.username = status.getUser().getScreenName();
                    	
                    	TwitterStreamTemplate._logger.debug(TwitterStreamTemplate.this.text);
	                   	ThingworxEvent event = new ThingworxEvent();
	         	        event.setTraceActive(ThreadLocalContext.isTraceActive());
	         	        event.setSecurityContext(ThreadLocalContext.getSecurityContext());
	         	        event.setSource(TwitterStreamTemplate.this.getName());
	         	        event.setEventName("TweetRecieved");
	         	        
	         	        InfoTable data = InfoTableInstanceFactory.createInfoTableFromDataShape((String)"TwitterRecievedEventDataShape");
	         	        ValueCollection values = new ValueCollection();
	         	        values.put("username", new StringPrimitive("@" + TwitterStreamTemplate.this.username));
	         	        values.put("text", new StringPrimitive(TwitterStreamTemplate.this.text));
	         	        data.addRow(values);
	         	        event.setEventData(data);
	         	        TwitterStreamTemplate.this.dispatchBackgroundEvent(event);
                    	TwitterStreamTemplate.this.getLastFoundTweet();
                    }
                }
                catch (Exception e) {
                    _logger.error(e.getMessage());
                    System.out.println(e.getMessage());
                }
            }

            public void onDeletionNotice(StatusDeletionNotice statusDeletionNotice) {
            	_logger.trace("Got a status deletion notice id:" + statusDeletionNotice.getStatusId());
            }

            public void onTrackLimitationNotice(int numberOfLimitedStatuses) {
            	_logger.trace("Got track limitation notice:" + numberOfLimitedStatuses);
            }

            public void onScrubGeo(long userId, long upToStatusId) {
            	_logger.trace("Got scrub_geo event userId:" + userId + " upToStatusId:" + upToStatusId);
            }

            public void onStallWarning(StallWarning warning) {
            	_logger.trace("Got stall warning:" + (Object)warning);
            }

            public void onException(Exception ex) {
                ex.printStackTrace();
            }
        };
        this.twitterStream.addListener(listener);

        TwitterFactory tf = new TwitterFactory(conf);
        this.twitter = tf.getInstance();

        this.initialized = true;
    }
    
	@Override
	public void onConnect() {
		connected = true;
		
	}

	@Override
	public void onDisconnect() {
		connected = false;		
	}

	@ThingworxServiceDefinition(name = "disconnectTwitterStream", description = "disconnect from Twitter Streaming api", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "NOTHING")
	public void disconnectTwitterStream() {
    	if (twitterStream == null) return;
    	twitterStream.cleanUp();
    	twitterStream.shutdown();
    	connected = false;
    	_logger.debug("Disconnected from twitter");
	}

	@ThingworxServiceDefinition(name = "isConnected", description = "Is twitter connected", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "BOOLEAN")
	public Boolean isConnected() {
			return connected;
	}

	@ThingworxServiceDefinition(name = "updateStatus", description = "Post a tweet to this account", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "STRING")
	public String updateStatus(
			@ThingworxServiceParameter(name = "message", description = "Status to apply to this account", 
			baseType = "STRING") String message) throws Exception
	{
	 	if (twitter == null)
    	{
            _logger.error("updateStatus: unable to update status, REST API not connected");
    		return "Twitter REST is null";
    	}
        Status r = twitter.updateStatus(message);
        return r.toString();
	}

	@ThingworxServiceDefinition(name = "getLastFoundTweet", description = "Get data from last matched tweet", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "Return name, text and time in an infotable", baseType = "INFOTABLE")
	public InfoTable getLastFoundTweet() throws Exception {

	        InfoTable data = InfoTableInstanceFactory.createInfoTableFromDataShape( (String)"TweetDataShape");
	        ValueCollection values = new ValueCollection();
	        values.put("name", new StringPrimitive("@" + this.username));
	        values.put("text", new StringPrimitive(this.text));
	        values.put("time", new DatetimePrimitive(DateTime.now()));
	        data.addRow(values);
	        return data;
	}

	@ThingworxServiceDefinition(name = "searchTwitter", description = "Set search string to scan twitter for and connect to api", category = "", isAllowOverride = false, aspects = {
			"isAsync:false" })
	@ThingworxServiceResult(name = "Result", description = "", baseType = "NOTHING")
	public void searchTwitter(
			@ThingworxServiceParameter(name = "searchString", description = "", baseType = "STRING", aspects = {
					"isRequired:true" }) String searchString) {

		try {
			this.setPropertyValue("searchString", (IPrimitiveType)new StringPrimitive(searchString));
		} catch (Exception e) {
			e.printStackTrace();
		}
        this.searchString = searchString;
        //if (!this.initialized) {
        	disconnectTwitterStream();
            this.connectTwitter();
        //}
        this.twitterStream.filter(new FilterQuery(new String[]{searchString}));
	}

	@Override
	public void onCleanUp() {		
	}
}
