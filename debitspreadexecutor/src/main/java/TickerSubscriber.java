import com.google.api.core.ApiFuture;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Quote;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.*;
import optiontrading.common.OPTIONTYPE;
import optiontrading.common.Option;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class TickerSubscriber {

    private static KiteConnect kiteConnect = null;
    private static final String projectId = "noted-reef-375804";
    private static final String topicId = "options-data-topic";
    private static Publisher publisher;
    private static Map<Long, String> instrumentMap;

    /** Demonstrates com.zerodhatech.ticker connection, subcribing for instruments, unsubscribing for instruments, set mode of tick data, com.zerodhatech.ticker disconnection*/
    public static void tickerSubscribe(KiteConnect kiteConnect, Map<String, Quote> quotes, ArrayList<Long> tokens) throws IOException, KiteException {
        /** To get live price use websocket connection.
         * It is recommended to use only one websocket connection at any point of time and make sure you stop connection, once user goes out of app.
         * custom url points to new endpoint which can be used till complete Kite Connect 3 migration is done. */
        instrumentMap = new HashMap<Long, String>();
        for ( Map.Entry<String,Quote> entry :  quotes.entrySet())
        {
            instrumentMap.put(entry.getValue().instrumentToken, entry.getKey());
        }
        final KiteTicker tickerProvider = new KiteTicker(kiteConnect.getAccessToken(), kiteConnect.getApiKey());
        initializeSubscribeAndGCPPublish();
        tickerProvider.setOnConnectedListener(new OnConnect() {
            @Override
            public void onConnected() {
                /** Subscribe ticks for token.
                 * By default, all tokens are subscribed for modeQuote.
                 * */
                tickerProvider.subscribe(tokens);
                tickerProvider.setMode(tokens, KiteTicker.modeFull);
            }
        });

        tickerProvider.setOnDisconnectedListener(new OnDisconnect() {
            @Override
            public void onDisconnected() {
                // your code goes here
            }
        });

        /** Set listener to get order updates.*/
        tickerProvider.setOnOrderUpdateListener(new OnOrderUpdate() {
            @Override
            public void onOrderUpdate(Order order) {
                System.out.println("order update "+order.orderId);
            }
        });

        /** Set error listener to listen to errors.*/
        tickerProvider.setOnErrorListener(new OnError() {
            @Override
            public void onError(Exception exception) {
                //handle here.
            }

            @Override
            public void onError(KiteException kiteException) {
                //handle here.
            }

            @Override
            public void onError(String s) {

            }

        });

        tickerProvider.setOnTickerArrivalListener(new OnTicks() {
            @Override
            public void onTicks(ArrayList<Tick> ticks) {
                NumberFormat formatter = new DecimalFormat();

                System.out.println("ticks size "+ticks.size());
                if(ticks.size() > 0) {
                    // 1. create index object
                    // 2. put json on pub sub
                    // 3. calculate ATM from index and put in blocking queue

                    // If tick is index,
                    //   calculate ATM option, keep one item in map of ATM keys
                    //   put it there if value changes + add new subscription
                    //   publish index object to pub sub
                    // If tick is Option value
                    //   and is ATM , publish it to pub sub, if its not ATM ignore.
                    System.out.println("last price "+ ticks.get(0).getLastTradedPrice() + " ITM/ATM Call Strike :" + getITMCall(ticks.get(0), instrumentMap));
                }
            }
        });
        // Make sure this is called before calling connect.
        tickerProvider.setTryReconnection(true);
        //maximum retries and should be greater than 0
        tickerProvider.setMaximumRetries(10);
        //set maximum retry interval in seconds
        tickerProvider.setMaximumRetryInterval(30);

        /** connects to com.zerodhatech.com.zerodhatech.ticker server for getting live quotes*/
        tickerProvider.connect();

        /** You can check, if websocket connection is open or not using the following method.*/
        boolean isConnected = tickerProvider.isConnectionOpen();
        System.out.println(isConnected);

        /** set mode is used to set mode in which you need tick for list of tokens.
         * Ticker allows three modes, modeFull, modeQuote, modeLTP.
         * For getting only last traded price, use modeLTP
         * For getting last traded price, last traded quantity, average price, volume traded today, total sell quantity and total buy quantity, open, high, low, close, change, use modeQuote
         * For getting all data with depth, use modeFull*/
        tickerProvider.setMode(tokens, KiteTicker.modeLTP);

        // Unsubscribe for a token.
        //tickerProvider.unsubscribe(tokens);

        // After using com.zerodhatech.com.zerodhatech.ticker, close websocket connection.
        //tickerProvider.disconnect();
    }

    private static void initializeSubscribeAndGCPPublish() throws IOException {
        TopicName topicName = TopicName.of(projectId, topicId);
        // Create a publisher instance with default settings bound to the topic
        publisher = Publisher.newBuilder(topicName).build();
    }

    public static String  getITMCall(Tick tick, Map<Long, String> instMap) {
        double lastPrice = tick.getLastTradedPrice();
        double ltpMod = Math.floorMod((long) lastPrice, (long)100);
        int x = (int) lastPrice - (int) ltpMod;
        long strikeForATMITM = 0;

        if( instMap.get(tick.getInstrumentToken()).equals("NSE:NIFTY 50"))
        {
            //Nifty call
            //For nifty range 17775 to 17794
            //The itm/ atm is strike 17750
            if(ltpMod >= 75 && ltpMod <=94)
            {
                strikeForATMITM = x + 50;
            }
            //For range 17795 to 17844
            //The itm/ atm is strike 17800
            else if(ltpMod <=44)
            {
                strikeForATMITM = x;
            }
            else if(ltpMod >= 95)
            {
                strikeForATMITM = x + 100;
            }

        }
        else if( instrumentMap.get(tick.getInstrumentToken()).equals("NSE:NIFTY BANK"))
        {
            //For nifty range 41092 to 41191
            //The itm/ atm is strike 41100

        }
        return String.format(""+ strikeForATMITM);
    }

    public static void publishTickerPrice(Tick tick)
            throws IOException, ExecutionException, InterruptedException {

        Gson gson;
        gson = new Gson();

        for (int i = 0; i < 25; i++) {


            try {


                Option option = new Option("NIFTY50", OPTIONTYPE.C, "NIFTY50 1800", 45, 1800, Instant.now().toEpochMilli(), "Option", Instant.now().toEpochMilli(), String.valueOf(i), "NIFTY 1800");

                ByteString data = ByteString.copyFromUtf8(gson.toJson(option, Option.class));
                PubsubMessage pubsubMessage = PubsubMessage.newBuilder().setData(data).build();

                // Once published, returns a server-assigned message id (unique within the topic)
                ApiFuture<String> messageIdFuture = publisher.publish(pubsubMessage);
                String messageId = messageIdFuture.get();
                System.out.println("Published message ID: " + messageId);
            } finally {
                if (publisher != null) {
                    // When finished with the publisher, shutdown to free up resources.
                    publisher.shutdown();
                    publisher.awaitTermination(1, TimeUnit.MINUTES);
                }
            }
        }
    }
}
