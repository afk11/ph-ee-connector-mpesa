package org.mifos.connector.mpesa.camel.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.json.JSONObject;
import org.mifos.connector.mpesa.auth.AccessTokenStore;
import org.mifos.connector.mpesa.dto.BuyGoodsPaymentRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

import static org.mifos.connector.mpesa.camel.config.CamelProperties.ACCESS_TOKEN;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.BUY_GOODS_REQUEST_BODY;
import static org.mifos.connector.mpesa.safaricom.config.SafaricomProperties.MPESA_BUY_GOODS_TRANSACTION_TYPE;


@Component
public class SafaricomRoutesBuilder extends RouteBuilder {

    @Value("${mpesa.api.passKey}")
    private String passKey;

    @Value("${mpesa.api.lipana}")
    private String buyGoodsBaseUrl;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccessTokenStore accessTokenStore;

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    public void configure() {

        /**
         * Rest endpoint to initiate payment for buy goods
         *
         * Sample request body: {
         *     "BusinessShortCode": 174379,
         *     "Amount": 1,
         *     "PartyA": 254708374149,
         *     "PartyB": 174379,
         *     "PhoneNumber": 254708374149,
         *     "CallBackURL": "https://mydomain.com/path",
         *     "AccountReference": "CompanyXLTD",
         *     "TransactionDesc": "Payment of X"
         *   }
         */
        from("rest:POST:/buygoods")
                .id("buy-goods-online")
                .process(exchange -> {
                    String body = exchange.getIn().getBody(String.class);
                    BuyGoodsPaymentRequestDTO buyGoodsPaymentRequestDTO = objectMapper.readValue(
                            body, BuyGoodsPaymentRequestDTO.class);

                    exchange.setProperty(BUY_GOODS_REQUEST_BODY, buyGoodsPaymentRequestDTO);
                    logger.info(body);
                    logger.info(buyGoodsPaymentRequestDTO.toString());

                })
                .to("direct:buy-goods-base");

        /**
         * Starts the payment flow
         *
         * Step1: Authenticate the user by initiating [get-access-token] flow
         * Step2: On successful [Step1], directs to [lipana-buy-goods] flow
         */
        from("direct:buy-goods-base")
                .id("buy-goods-base")
                .log(LoggingLevel.INFO, "Starting buy goods flow")
                .to("direct:get-access-token")
                .process(exchange -> exchange.setProperty(ACCESS_TOKEN, accessTokenStore.getAccessToken()))
                .log(LoggingLevel.INFO, "Got access token, moving on to API call.")
                .to("direct:lipana-buy-goods");

        /**
         * Takes the access toke and payment request and forwards the requests to lipana API.
         * [Timestamp], [Password] and [TransactionType] are set in runtime and request is forwarded to lipana endpoint.
         */
        from("direct:lipana-buy-goods")
                .removeHeader("*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Type", constant("application/json"))
                .setHeader("Authorization", simple("Bearer ${exchangeProperty."+ACCESS_TOKEN+"}"))
                .setBody(exchange -> {
                    BuyGoodsPaymentRequestDTO buyGoodsPaymentRequestDTO =
                            (BuyGoodsPaymentRequestDTO) exchange.getProperty(BUY_GOODS_REQUEST_BODY);

                    Long timestamp = getTimestamp();
                    String password = getPassword("" + buyGoodsPaymentRequestDTO.getBusinessShortCode(),
                            passKey,
                            "" + timestamp);

                    buyGoodsPaymentRequestDTO.setTimestamp(timestamp);
                    buyGoodsPaymentRequestDTO.setPassword(password);
                    buyGoodsPaymentRequestDTO.setTransactionType(MPESA_BUY_GOODS_TRANSACTION_TYPE);

                    logger.info(buyGoodsPaymentRequestDTO.toString());
                    logger.info(accessTokenStore.getAccessToken());

                    return buyGoodsPaymentRequestDTO;
                })
                .process(exchange -> {
                    logger.info((String) exchange.getIn().getHeader("Authorization"));
                })
                .marshal().json(JsonLibrary.Jackson)
                .toD(buyGoodsBaseUrl+"?bridgeEndpoint=true");
    }

    /**
     * Generated the password using the businessShortCode, passKey and timestamp
     * @param businessShortCode
     * @param passKey
     * @param timestamp
     * @return password
     */
    private String getPassword(String businessShortCode, String passKey, String timestamp) {
        String data = businessShortCode + passKey + timestamp;
        String password = toBase64(data);
        logger.info("Password: "+password);
        return password;
    }

    /**
     * returns the local epoch time
     */
    private Long getTimestamp(){
        return LocalDate.now().toEpochDay();
    }

    /**
     * Converts the string data into base64 encode string
     * @param data
     * @return base64 of [data]
     */
    private String toBase64(String data) {
        return Base64.getEncoder().encodeToString(data.getBytes());
    }
}
