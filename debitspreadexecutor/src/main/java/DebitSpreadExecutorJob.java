import awesome.code.base.properties.IPropertiesProvider;
import awesome.code.base.service.IJob;
import awesome.code.base.service.exception.ServiceException;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;


import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import kitesubscribers.KiteConnectProvider;
import org.apache.commons.lang3.StringUtils;
import debitspread.*;

public class DebitSpreadExecutorJob implements IJob {

    private KiteConnect kiteConnect;
    private List<String> indexTobSubscribe;
    private ZerodhaTradeEngine engine;
    private boolean backTest;

    @Override
    public void init(IPropertiesProvider propertiesProvider) throws ServiceException {
        System.out.println("in option loader jon init method");
        String projectId = propertiesProvider.getStringProperty("option.trading.project.id", null);
        String subscriptionId = propertiesProvider.getStringProperty("option.trading.subscription.id", "options-data-topic-sub");
        String instrumentString = propertiesProvider.getStringProperty("option.trading.eligible.instruments", null);
        this.indexTobSubscribe = Arrays.stream(StringUtils.split(instrumentString, ",")).collect(Collectors.toList());
        // kiteSessionManager = new KiteSessionManager();
        //KiteConnect kite = session.authenticate();

        backTest = propertiesProvider.getBooleanProperty("option.trading.straddler.run.backtest", false);
        KiteConnectProvider kiteConnectProvider = new KiteConnectProvider(propertiesProvider);
        try {
            this.kiteConnect = kiteConnectProvider.getKiteSDKForUserID();
            TradeConfig config = TradeConfig.defaultConfig();
            Logger.info("Config: " + config);

            engine = new ZerodhaTradeEngine(config, kiteConnect);
        } catch (IOException | KiteException exception) {
            System.out.println("Exception while intializing kit connect");
            throw new ServiceException(exception);
        }
    }

    @Override
    public void executeJob() throws ServiceException {
        // --login flag: just print the Kite OAuth URL and exit
//        if (args.length > 0 && args[0].equals("--login")) {
//            KiteSessionManager.printLoginUrl();
//            return;
//        }


        Logger.info("═══════════════════════════════════════════════════════════");
        Logger.info("   NIFTY50 Short Strangle Algorithm — Zerodha Edition");
        Logger.info("═══════════════════════════════════════════════════════════");

        // Authenticate with Zerodha Kite Connect


        // Load NFO instrument dump (needed for option chain resolution)
        try {
            engine.initialize();
        } catch (KiteException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Graceful shutdown on Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Logger.warn("Shutdown signal — stopping engine and squaring off if needed.");
            engine.stop();
        }));

        engine.run();
    }



    private List<Instrument> getAllInstruments() {
        List<Instrument> listOfAllNSEInstruments = new ArrayList<>();
        try (BufferedInputStream inputStream = new BufferedInputStream(new URL("https://api.kite.trade/instruments").openStream());
             InputStreamReader input = new InputStreamReader(inputStream);
             BufferedReader reader = new BufferedReader(input)
        ) {
            // create csvReader object and skip first Line
            CSVReader csvReader = new CSVReaderBuilder(reader)
                    .withSkipLines(1)
                    .build();
            List<String[]> allData = csvReader.readAll();

            for (String[] row : allData) {
                Instrument i = new Instrument();
                if (row[0] != null && row[2] != null && row[3] != null && row[9] != null && row[6] != null) {
                    i.setInstrument_token(Long.parseLong(row[0]));
                    i.setTradingsymbol((row[2]));
                    i.setName((row[3]));
                    i.setStrike((row[6]));
                    i.setInstrument_type((row[9]));
                    listOfAllNSEInstruments.add(i);
                }

            }

        } catch (IOException e) {
            // handles IO exceptions
            System.out.println("Exception while getting all instrument");
            System.exit(0);
        }

        return listOfAllNSEInstruments;
    }
}
