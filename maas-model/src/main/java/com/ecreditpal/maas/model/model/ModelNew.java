package com.ecreditpal.maas.model.model;


import com.ecreditpal.maas.common.WorkDispatcher;
import com.ecreditpal.maas.common.schedule.impl.ResReload;
import com.ecreditpal.maas.model.variable.Variable;

import com.ecreditpal.maas.model.variable.VariableConfiguration;
import org.dmg.pmml.DataType;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.PMML;
import org.jpmml.evaluator.*;
import org.json.JSONObject;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by xibu on 9/28/16.
 */
public class ModelNew extends ResReload {
    private final static Logger log = LoggerFactory.getLogger(ModelNew.class);
    public String configPath;
    public String modelName;
    public List<Variable> variableList;
    public Map<String, Variable> variableMap;
    public Map<String, String> inputMap;
    public Map<String, Object> inputObjMap;
    public String inputJsonString;
    public String packagePath = "com.ecreditpal.modelserv.variables.";
    public List<HashMap<FieldName, String>> variablePool;
    public CountDownLatch cdl;
    private int CDL_TIMEOUT_SEC = 5;
    public static String configDir = "itsrobin"+"src/main/resources";

    public static WorkDispatcher workDispatcher = new WorkDispatcher.Builder().
            corePoolSize(Integer.valueOf("itsrobin"+"corePoolSize")).
            maxPoolSize(Integer.valueOf("itsrobin"+"maxPoolSize")).
            keepAliveTime(60).
            queue(new LinkedBlockingQueue<>(Integer.valueOf("itsrobin"+"capacity"))).build();

    //Runtime.getRuntime().availableProcessors()

    //loaded model will be put into this map;
    public static Map<String, ModelNew> modelInstances = new ConcurrentHashMap<>();


    public static PMML pmml;
    public static Evaluator evaluator;

    public PMML getPmml() {
        return pmml;
    }

    public static void setPmml(PMML input_pmml) {
        pmml = input_pmml;
    }

    public static Evaluator getEvaluator() {
        return evaluator;
    }

    public static void setEvaluator(Evaluator input_evaluator) {
        evaluator = input_evaluator;
    }


    /**
     * automatically load model resource
     */
    @Override
    public void execute(JobExecutionContext jobExecutionContext) {
        log.info("automatically refresh " + modelInstances.size() + "  model resource which registered in the modelInstances");
        log.info(workDispatcher.getModelExecutor().toString());
        for (ModelNew modelNew : modelInstances.values()) {
            modelNew.work();
        }
    }


    public void register(ModelNew modelNew) {
        modelInstances.put(modelNew.toString(), modelNew);
    }


    //this method will be invoked by subclass
    public void work() {
    }

    //input is always JSONString passed from EndPoint
    public Object run(String input) throws JAXBException {
        setInputJson(input);
        inputParse();
        try {
            invokeVariable();
        } catch (Exception e) {
        }
        Object model_result = executeModel();
        return model_result;
    }

    /**
     * 如果入参包含复杂对象,可以使用该方法传入参数
     * @param map 外部构造的map对象
     */
    public Object run(Map<String,Object> map) {
        inputMapParse(map);
        try {
            invokeVariable();
        } catch (Exception e) {
        }
        return executeModel();
    }

    public void inputParse() {
        //overwrite in specific model class if special input treatment required
        inputJsonParse(getInputJson());
    }

    public void inputJsonParse(String inputJsonString) {
        JSONObject json = new JSONObject(inputJsonString);
        Iterator<String> keys = json.keys();
        HashMap<FieldName, String> inputVarList = new HashMap<FieldName, String>();
        while (keys.hasNext()) {
            String key = keys.next();
            String val = json.get(key).toString();

            if (val.equals("null")) {
                inputMap.put(key, null);
            } else {
                inputMap.put(key, val);
            }
        }
    }

    /**
     * 如果入参包含复杂对象,可以使用该方法传入参数
     * @param map 外部构造的map对象
     */
    private void inputMapParse(Map<String,Object> map) {
        for (String s : map.keySet()) {
            inputObjMap.put(s,map.get(s));
        }
    }

    private void invokeVariable() {
        //overwrite in specific model class if special invoke order required
        //multi-thread with CountDownLatch
        cdl = new CountDownLatch(variableList.size());
        if (inputMap != null) {
            for (Variable v : variableList) {
                v.execute(inputMap, cdl);
            }
        } else{
            for (Variable v : variableList) {
                v.execute(cdl,inputObjMap);
            }
        }
        try {
            cdl.await(CDL_TIMEOUT_SEC, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    //Model base class Should not be called
    public Object executeModel() {
        return -999.0;
    }

    public List<Variable> loadVariableConfig() throws JAXBException {
        File xml = new File(configPath);
        if (!xml.exists()) {
            throw new RuntimeException("Cannot find variable configuration file " + configPath + " in the classpath");
        }
        JAXBContext jc = JAXBContext.newInstance(VariableConfiguration.class);
        Unmarshaller unmarshaller = jc.createUnmarshaller();
        VariableConfiguration conf = (VariableConfiguration) unmarshaller.unmarshal(xml);
        modelName = conf.getModel();

        return conf.getVariables();
    }

    /**
     * pmml model used only
     * run after all the variable calculations are done
     * prepare model variables for model execution
     * @param evaluator
     * @param variableMap
     * @return variable map <name, variable value>
     */
    public List<Map<FieldName, FieldValue>> prepareModelInput(Evaluator evaluator, Map<String, Variable> variableMap) {
        List<FieldName> groupFields = evaluator.getGroupFields();
        List<Map<FieldName, Object>> stringRows = new ArrayList<Map<FieldName, Object>>();
        Map<FieldName, Object> stringRow = new LinkedHashMap<FieldName, Object>();

        for (Map.Entry<String, Variable> entry : variableMap.entrySet()) {
            FieldName name = FieldName.create(entry.getKey());
            String value = entry.getValue().getValue();
            if (("").equals(value) || ("NA").equals(value) || ("N/A").equals(value)) {
                value = null;
            }
            stringRow.put(name, value);
        }

        stringRows.add(stringRow);
        if (groupFields.size() == 1) {
            FieldName groupField = groupFields.get(0);

            stringRows = EvaluatorUtil.groupRows(groupField, stringRows);
        } else if (groupFields.size() > 1) {
            throw new EvaluationException();
        }

        List<Map<FieldName, FieldValue>> fieldValueRows = new ArrayList<Map<FieldName, FieldValue>>();

        for (Map<FieldName, Object> sr : stringRows) {
            Map<FieldName, FieldValue> fieldValueRow = new LinkedHashMap<FieldName, FieldValue>();

            Collection<Map.Entry<FieldName, Object>> entries = sr.entrySet();
            for (Map.Entry<FieldName, Object> entry : entries) {
                FieldName name = entry.getKey();
                // Pre Data process: for numeric variable convert non-double
                // value to null.
                if (evaluator.getDataField(name).getDataType() == DataType.DOUBLE) {
                    try {
                        Double.parseDouble((String) entry.getValue());
                    } catch (Exception e) {
                        entry.setValue(null);
                    }
                }
                FieldValue value = EvaluatorUtil.prepare(evaluator, name, entry.getValue());
                fieldValueRow.put(name, value);
            }

            fieldValueRows.add(fieldValueRow);
        }

        return fieldValueRows;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }

    public List<Variable> getVariableList() {
        return variableList;
    }

    public void setVariableList(List<Variable> variableList) {
        this.variableList = variableList;
    }

    public String getInputJson() {
        return inputJsonString;
    }

    public void setInputJson(String inputJson) {
        this.inputJsonString = inputJson;
    }

    public Map<String, String> getInputMap() {
        return inputMap;
    }

    public void setInputMap(Map<String, String> inputMap) {
        this.inputMap = inputMap;
    }

    public Map<String, Object> getInputObjMap() {
        return inputObjMap;
    }

    public void setInputObjMap(Map<String, Object> inputObjMap) {
        this.inputObjMap = inputObjMap;
    }
}
