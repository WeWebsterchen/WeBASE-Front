/**
 * Copyright 2012-2019 the original author or authors.
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
package com.webank.webase.front.transaction;

import static com.webank.webase.front.base.ConstantCode.GROUPID_NOT_EXIST;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.StringUtils;
import org.fisco.bcos.channel.client.TransactionSucCallback;
import org.fisco.bcos.web3j.abi.TypeReference;
import org.fisco.bcos.web3j.abi.datatypes.Function;
import org.fisco.bcos.web3j.abi.datatypes.Type;
import org.fisco.bcos.web3j.crypto.Credentials;
import org.fisco.bcos.web3j.crypto.ExtendedRawTransaction;
import org.fisco.bcos.web3j.crypto.ExtendedTransactionEncoder;
import org.fisco.bcos.web3j.crypto.RawTransaction;
import org.fisco.bcos.web3j.crypto.Sign.SignatureData;
import org.fisco.bcos.web3j.crypto.TransactionEncoder;
import org.fisco.bcos.web3j.precompile.cns.CnsInfo;
import org.fisco.bcos.web3j.precompile.cns.CnsService;
import org.fisco.bcos.web3j.protocol.ObjectMapperFactory;
import org.fisco.bcos.web3j.protocol.Web3j;
import org.fisco.bcos.web3j.protocol.core.Request;
import org.fisco.bcos.web3j.protocol.core.methods.response.AbiDefinition;
import org.fisco.bcos.web3j.protocol.core.methods.response.SendTransaction;
import org.fisco.bcos.web3j.protocol.core.methods.response.TransactionReceipt;
import org.fisco.bcos.web3j.protocol.exceptions.TransactionException;
import org.fisco.bcos.web3j.tx.exceptions.ContractCallException;
import org.fisco.bcos.web3j.utils.Numeric;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.webase.front.base.ConstantCode;
import com.webank.webase.front.base.Constants;
import com.webank.webase.front.base.exception.FrontException;
import com.webank.webase.front.contract.CommonContract;
import com.webank.webase.front.keystore.EncodeInfo;
import com.webank.webase.front.keystore.KeyStoreInfo;
import com.webank.webase.front.keystore.KeyStoreService;
import com.webank.webase.front.util.AbiUtil;
import com.webank.webase.front.util.CommonUtils;
import com.webank.webase.front.util.ContractAbiUtil;
import lombok.extern.slf4j.Slf4j;


/**
 * TransService.
 */
@Slf4j
@Service
public class TransService {

    @Autowired
    private Map<Integer, Web3j> web3jMap;
    @Autowired
    private Map<Integer, CnsService> cnsServiceMap;
    @Autowired
    private KeyStoreService keyStoreService;
    @Autowired
    private Map<String, String> cnsMap;
    @Autowired
    private Constants constants;

    /**
     * transHandle.
     *
     * @param req request
     */
    public Object transHandle(ReqTransHandle req) throws Exception {

        boolean ifExisted;
        // Check if contractAbi existed
        if (req.getVersion() != null) {
            ifExisted =
                    ContractAbiUtil.ifContractAbiExisted(req.getContractName(), req.getVersion());
        } else {
            ifExisted = ContractAbiUtil.ifContractAbiExisted(req.getContractName(),
                    req.getContractAddress().substring(2));
        }

        if (!ifExisted) {
            // check and save abi
            checkAndSaveAbiFromCns(req);
        }

        Object baseRsp = dealWithtrans(req);
        log.info("transHandle end. name:{} func:{} baseRsp:{}", req.getContractName(),
                req.getFuncName(), JSON.toJSONString(baseRsp));
        return baseRsp;
    }

    /**
     * checkAndSaveAbiFromCns.
     *
     * @param req request
     */
    public void checkAndSaveAbiFromCns(ReqTransHandle req) throws Exception {
        List<CnsInfo> cnsInfoList = null;
        CnsService cnsService = cnsServiceMap.get(req.getGroupId());
        if (cnsService == null) {
            throw new FrontException(GROUPID_NOT_EXIST);
        }
        if (req.getVersion() != null) {
            cnsInfoList =
                    cnsService.queryCnsByNameAndVersion(req.getContractName(), req.getVersion());
        } else {
            cnsInfoList = cnsService.queryCnsByNameAndVersion(req.getContractName(),
                    req.getContractAddress().substring(2));
        }
        // transaction request
        if (cnsInfoList == null) {
            throw new FrontException("can not get cns information from chain!");
        }
        log.info("cnsinfo" + cnsInfoList.get(0).getAddress());
        ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();
        List abiDefinitionList = objectMapper.readValue(cnsInfoList.get(0).getAbi(), objectMapper
                .getTypeFactory().constructCollectionType(List.class, AbiDefinition.class));
        // check if contract has been deployed
        if (StringUtils.isBlank(cnsInfoList.get(0).getAbi())) {
            throw new FrontException(ConstantCode.CONTRACT_NOT_DEPLOY_ERROR);
        }

        // save abi
        ContractAbiUtil.setContractWithAbi(req.getContractName(),
                req.getVersion() == null ? req.getContractAddress().substring(2) : req.getVersion(),
                abiDefinitionList, true);
    }

    /**
     * transaction request.
     *
     * @param req request
     */
    public Object dealWithtrans(ReqTransHandle req) throws FrontException {
        log.info("dealWithtrans start. ReqTransHandle:[{}]", JSON.toJSONString(req));
        Object result = null;
        String contractName = req.getContractName();
        String version = req.getVersion();
        String address = req.getContractAddress();
        String funcName = req.getFuncName();
        List<Object> params = req.getFuncParam();
        int groupId = req.getGroupId();
        if (StringUtils.isBlank(version) && StringUtils.isNotBlank(address)) {
            version = address.substring(2);
        }

        // if function is constant
        String constant = ContractAbiUtil.ifConstantFunc(contractName, funcName, version);
        if (constant == null) {
            log.warn("dealWithtrans fail. contract name:{} func:{} version:{} is not existed",
                    contractName, funcName, version);
            throw new FrontException(ConstantCode.IN_FUNCTION_ERROR);
        }

        // inputs format
        List<String> funcInputTypes =
                ContractAbiUtil.getFuncInputType(contractName, funcName, version);
        if (funcInputTypes == null || funcInputTypes.size() != params.size()) {
            log.warn("dealWithtrans fail. funcInputTypes:{}, params:{}", funcInputTypes, params);
            throw new FrontException(ConstantCode.IN_FUNCPARAM_ERROR);
        }
        List<Type> finalInputs = AbiUtil.inputFormat(funcInputTypes, params);

        // outputs format
        List<String> funOutputTypes =
                ContractAbiUtil.getFuncOutputType(contractName, funcName, version);
        List<TypeReference<?>> finalOutputs = AbiUtil.outputFormat(funOutputTypes);

        // get privateKey
        Credentials credentials = null;
        if ("true".equals(constant)) {
            KeyStoreInfo keyStoreInfo = keyStoreService.createPrivateKey(false);
            credentials = Credentials.create(keyStoreInfo.getPrivateKey());
        } else {
            credentials = keyStoreService.getCredentials(req.getUser(), req.getUseAes());
        }

        // contract load
        CommonContract commonContract;
        Web3j web3j = web3jMap.get(groupId);
        if (web3j == null) {
            new FrontException(GROUPID_NOT_EXIST);
        }
        if (address == null) {
            address = cnsMap.get(contractName + ":" + version);
        }

        if (address != null) {
            commonContract = CommonContract.load(address, web3j, credentials, Constants.GAS_PRICE,
                    Constants.GAS_LIMIT);
        } else {
            commonContract = CommonContract.loadByName(contractName + Constants.SYMPOL + version,
                    web3j, credentials, Constants.GAS_PRICE, Constants.GAS_LIMIT);
        }
        // request
        Function function = new Function(funcName, finalInputs, finalOutputs);
        if ("true".equals(constant)) {
            result = execCall(funOutputTypes, function, commonContract);
        } else {
            result = execTransaction(function, commonContract);
        }
        return result;
    }

    /**
     * execCall.
     *
     * @param funOutputTypes list
     * @param function function
     * @param commonContract contract
     */
    public static Object execCall(List<String> funOutputTypes, Function function,
            CommonContract commonContract) throws FrontException {
        try {
            List<Type> typeList = commonContract.execCall(function);
            Object result = null;
            if (typeList.size() > 0) {
                result = AbiUtil.callResultParse(funOutputTypes, typeList);
            }
            return result;
        } catch (IOException | ContractCallException e) {
            log.error("execCall failed.", e);
            throw new FrontException(ConstantCode.TRANSACTION_QUERY_FAILED.getCode(),
                    e.getMessage());
        }
    }

    /**
     * execTransaction.
     *
     * @param function function
     * @param commonContract contract
     */
    public static TransactionReceipt execTransaction(Function function,
            CommonContract commonContract) throws FrontException {
        TransactionReceipt transactionReceipt = null;
        try {
            transactionReceipt = commonContract.execTransaction(function);
        } catch (IOException | TransactionException | ContractCallException e) {
            log.error("execTransaction failed.", e);
            throw new FrontException(ConstantCode.TRANSACTION_SEND_FAILED.getCode(),
                    e.getMessage());
        }
        return transactionReceipt;
    }

    /**
     * signMessage.
     * 
     * @param groupId id
     * @param contractAddress info
     * @param data info
     * @return
     */
    public String signMessage(int groupId, int userId, String contractAddress, String data)
            throws IOException, FrontException {
        Random r = new Random();
        BigInteger randomid = new BigInteger(250, r);
        BigInteger blockLimit = web3jMap.get(groupId).getBlockNumberCache();
        String versionContent = web3jMap.get(groupId).getNodeVersion().sendForReturnString();
        String signMsg = "";
        if (versionContent.contains("2.0.0-rc1") || versionContent.contains("release-2.0.1")) {
            RawTransaction rawTransaction = RawTransaction.createTransaction(randomid,
                    constants.GAS_PRICE, constants.GAS_LIMIT, blockLimit, contractAddress,
                    BigInteger.ZERO, data);
            byte[] encodedTransaction = TransactionEncoder.encode(rawTransaction);
            String encodedDataStr = new String(encodedTransaction);

            EncodeInfo encodeInfo = new EncodeInfo();
            encodeInfo.setUserId(userId);
            encodeInfo.setEncodedDataStr(encodedDataStr);
            String signDataStr = keyStoreService.getSignDate(encodeInfo);
            if (StringUtils.isBlank(signDataStr)) {
                log.warn("deploySend get sign data error.");
                return null;
            }

            SignatureData signData = CommonUtils.stringToSignatureData(signDataStr);
            byte[] signedMessage = TransactionEncoder.encode(rawTransaction, signData);
            signMsg = Numeric.toHexString(signedMessage);
        } else {
            String chainId = (String) JSONObject.parseObject(versionContent).get("Chain Id");
            ExtendedRawTransaction extendedRawTransaction =
                    ExtendedRawTransaction.createTransaction(randomid, constants.GAS_PRICE,
                            constants.GAS_LIMIT, blockLimit, contractAddress, BigInteger.ZERO, data,
                            new BigInteger(chainId), BigInteger.valueOf(groupId), "");
            byte[] encodedTransaction = ExtendedTransactionEncoder.encode(extendedRawTransaction);
            String encodedDataStr = new String(encodedTransaction);

            EncodeInfo encodeInfo = new EncodeInfo();
            encodeInfo.setUserId(userId);
            encodeInfo.setEncodedDataStr(encodedDataStr);
            String signDataStr = keyStoreService.getSignDate(encodeInfo);
            if (StringUtils.isBlank(signDataStr)) {
                log.warn("deploySend get sign data error.");
                return null;
            }

            SignatureData signData = CommonUtils.stringToSignatureData(signDataStr);
            byte[] signedMessage =
                    ExtendedTransactionEncoder.encode(extendedRawTransaction, signData);
            signMsg = Numeric.toHexString(signedMessage);
        }
        return signMsg;
    }

    /**
     * send message to node.
     * 
     * @param signMsg signMsg
     * @param future future
     */
    public void sendMessage(int groupId, String signMsg,
            final CompletableFuture<TransactionReceipt> future) throws IOException {
        Request<?, SendTransaction> request = web3jMap.get(groupId).sendRawTransaction(signMsg);
        request.setNeedTransCallback(true);
        request.setTransactionSucCallback(new TransactionSucCallback() {
            @Override
            public void onResponse(TransactionReceipt receipt) {
                log.info("onResponse receipt:{}", receipt);
                future.complete(receipt);
                return;
            }
        });
        request.send();
    }
}
