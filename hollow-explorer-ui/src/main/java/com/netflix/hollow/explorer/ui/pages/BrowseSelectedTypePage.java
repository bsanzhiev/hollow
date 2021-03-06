/*
 *
 *  Copyright 2017 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.hollow.explorer.ui.pages;

import com.netflix.hollow.core.index.HollowPrimaryKeyIndex;
import com.netflix.hollow.core.index.key.PrimaryKey;
import com.netflix.hollow.core.read.HollowReadFieldUtils;
import com.netflix.hollow.core.read.engine.HollowTypeReadState;
import com.netflix.hollow.core.read.engine.HollowTypeStateListener;
import com.netflix.hollow.core.read.engine.object.HollowObjectTypeReadState;
import com.netflix.hollow.core.schema.HollowObjectSchema;
import com.netflix.hollow.core.schema.HollowSchema;
import com.netflix.hollow.core.schema.HollowSchema.SchemaType;
import com.netflix.hollow.explorer.ui.HollowExplorerUI;
import com.netflix.hollow.explorer.ui.model.QueryResult;
import com.netflix.hollow.explorer.ui.model.TypeKey;
import com.netflix.hollow.tools.stringifier.HollowRecordJsonStringifier;
import com.netflix.hollow.tools.stringifier.HollowRecordStringifier;
import com.netflix.hollow.ui.HollowUISession;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.apache.velocity.VelocityContext;

public class BrowseSelectedTypePage extends HollowExplorerPage {

    public BrowseSelectedTypePage(HollowExplorerUI ui) {
        super(ui, "browse-selected-type.vm");
    }

    @Override
    protected void setUpContext(HttpServletRequest req, HollowUISession session, VelocityContext ctx) {
        HollowTypeReadState typeState = ui.getStateEngine().getTypeState(req.getParameter("type"));
        
        HollowPrimaryKeyIndex idx = findPrimaryKeyIndex(typeState);
        
        int page = req.getParameter("page") == null ? 0 : Integer.parseInt(req.getParameter("page"));
        int pageSize = req.getParameter("pageSize") == null ? 20 : Integer.parseInt(req.getParameter("pageSize"));
        int startRec = page * pageSize;
        
        String displayFormat = "json".equals(req.getParameter("display")) ? "json" : "text";
        
        BitSet selectedOrdinals = typeState.getPopulatedOrdinals();

        if("true".equals(req.getParameter("clearQuery")))
            session.clearAttribute("query-result");

        if(session.getAttribute("query-result") != null) {
            QueryResult queryResult = (QueryResult) session.getAttribute("query-result");
            queryResult.recalculateIfNotCurrent(ui.getStateEngine());
            
            selectedOrdinals = queryResult.getQueryMatches().get(typeState.getSchema().getName());
            if(selectedOrdinals == null)
                selectedOrdinals = new BitSet();
            
            ctx.put("filteredByQuery", queryResult.getQueryDisplayString());
        }
        
        int currentOrdinal = selectedOrdinals.nextSetBit(0);
        for(int i=0;i<startRec;i++) {
            currentOrdinal = selectedOrdinals.nextSetBit(currentOrdinal+1);
        }
        
        PrimaryKey primaryKey = getPrimaryKey(typeState.getSchema());
        int fieldPathIndexes[][] = getFieldPathIndexes(primaryKey); 
        
        List<TypeKey> keys = new ArrayList<TypeKey>(pageSize);
        
        for(int i=0;i<pageSize && currentOrdinal != -1;i++) {
            keys.add(getKey(startRec + i, typeState, currentOrdinal, fieldPathIndexes));
            currentOrdinal = selectedOrdinals.nextSetBit(currentOrdinal+1);
        }

        
        String key = req.getParameter("key") == null ? "" : req.getParameter("key");
        Object parsedKey[] = null;
        try {
            parsedKey = parseKey(primaryKey, key);
        } catch(Exception e) {
            key = "";
        }
        
        int ordinal = req.getParameter("ordinal") == null ? -1 : Integer.parseInt(req.getParameter("ordinal"));
        if("".equals(key) && ordinal != -1) {
            displayRecord(ctx, displayFormat, typeState, ordinal);
            if(fieldPathIndexes != null)
                key = getKey(-1, typeState, ordinal, fieldPathIndexes).getKey();
        } else if(!"".equals(key)) {
            if(ordinal != -1 && selectedOrdinals.get(ordinal) && recordKeyEquals(typeState, ordinal, parsedKey, fieldPathIndexes)) {
                displayRecord(ctx, displayFormat, typeState, ordinal);
            } else {
                if(idx != null) {
                    ordinal = findOrdinal(idx, key);
                    if(ordinal != -1)
                        displayRecord(ctx, displayFormat, typeState, ordinal);
                    else
                        ctx.put("selectedRecordData", "ERROR: Key " + key + " was not found!");
                } else {
                    /// scan through all records
                    ordinal = selectedOrdinals.nextSetBit(0);
                    while(ordinal != -1) {
                        if(recordKeyEquals(typeState, ordinal, parsedKey, fieldPathIndexes)) {
                            displayRecord(ctx, displayFormat, typeState, ordinal);
                            break;
                        }
                        
                        ordinal = selectedOrdinals.nextSetBit(ordinal+1);
                    }
                }
            }
        }
        
        int numRecords = selectedOrdinals.cardinality();

        ctx.put("keys", keys);
        ctx.put("page", page);
        ctx.put("pageSize", pageSize);
        ctx.put("numPages", ((numRecords - 1) / pageSize) + 1);
        ctx.put("numRecords", numRecords);
        ctx.put("type", typeState.getSchema().getName());
        ctx.put("key", key);
        ctx.put("ordinal", ordinal);
        ctx.put("display", displayFormat);
    }

    private void displayRecord(VelocityContext ctx, String displayFormat, HollowTypeReadState typeState, int ordinal) {
        if("json".equals(displayFormat)) {
            ctx.put("selectedRecordData", new HollowRecordJsonStringifier().stringify(ui.getStateEngine(), typeState.getSchema().getName(), ordinal));
        } else {
            ctx.put("selectedRecordData", new HollowRecordStringifier().stringify(ui.getStateEngine(), typeState.getSchema().getName(), ordinal));
        }
    }

    private TypeKey getKey(int recordIdx, HollowTypeReadState typeState, int ordinal, int[][] fieldPathIndexes) {
        if(fieldPathIndexes != null) {
            StringBuilder keyBuilder = new StringBuilder();
            
            HollowObjectTypeReadState objState = (HollowObjectTypeReadState)typeState;
            
            for(int i=0;i<fieldPathIndexes.length;i++) {
                int curOrdinal = ordinal;
                HollowObjectTypeReadState curState = objState;
                
                for(int j=0;j<fieldPathIndexes[i].length - 1;j++) {
                    curOrdinal = curState.readOrdinal(curOrdinal, fieldPathIndexes[i][j]);
                    curState = (HollowObjectTypeReadState) curState.getSchema().getReferencedTypeState(fieldPathIndexes[i][j]);
                }
                
                if(i > 0)
                    keyBuilder.append(":");
                
                keyBuilder.append(HollowReadFieldUtils.fieldValueObject(curState, curOrdinal, fieldPathIndexes[i][fieldPathIndexes[i].length-1]));
            }
            
            return new TypeKey(recordIdx, ordinal, keyBuilder.toString());
        }
        
        return new TypeKey(recordIdx, ordinal, "", "ORDINAL:"+ordinal);
    }
    
    private PrimaryKey getPrimaryKey(HollowSchema schema) {
        if(schema.getSchemaType() == SchemaType.OBJECT)
            return ((HollowObjectSchema)schema).getPrimaryKey();
        return null;
    }

    private int[][] getFieldPathIndexes(PrimaryKey primaryKey) {
        if(primaryKey != null) {
            int fieldPathIndexes[][] = new int[primaryKey.numFields()][];
            for(int i=0;i<primaryKey.numFields();i++) {
                fieldPathIndexes[i] = primaryKey.getFieldPathIndex(ui.getStateEngine(), i);
            }
            return fieldPathIndexes;
        }
        
        return null;
    }
    
    private HollowPrimaryKeyIndex findPrimaryKeyIndex(HollowTypeReadState typeState) {
        if(getPrimaryKey(typeState.getSchema()) == null)
            return null;
        
        for(HollowTypeStateListener listener : typeState.getListeners()) {
            if(listener instanceof HollowPrimaryKeyIndex) {
                if(((HollowPrimaryKeyIndex) listener).getPrimaryKey().equals(getPrimaryKey(typeState.getSchema())))
                    return (HollowPrimaryKeyIndex)listener;
            }
        }
        
        return null;
    }
    
    private int findOrdinal(HollowPrimaryKeyIndex idx, String keyString) {
        Object[] key = parseKey(idx.getPrimaryKey(), keyString);
        
        return idx.getMatchingOrdinal(key);
    }

    private Object[] parseKey(PrimaryKey primaryKey, String keyString) {
        String fields[] = keyString.split(":");
        
        Object key[] = new Object[fields.length];
        
        for(int i=0;i<fields.length;i++) {
            switch(primaryKey.getFieldType(ui.getStateEngine(), i)) {
            case BOOLEAN:
                key[i] = Boolean.parseBoolean(fields[i]);
                break;
            case STRING:
                key[i] = fields[i];
                break;
            case INT:
            case REFERENCE:
                key[i] = Integer.parseInt(fields[i]);
                break;
            case LONG:
                key[i] = Long.parseLong(fields[i]);
                break;
            case DOUBLE:
                key[i] = Double.parseDouble(fields[i]);
                break;
            case FLOAT:
                key[i] = Float.parseFloat(fields[i]);
                break;
            case BYTES:
                key[i] = null; //TODO
            }
        }
        return key;
    }
    
    private boolean recordKeyEquals(HollowTypeReadState typeState, int ordinal, Object[] key, int[][] fieldPathIndexes) {
        HollowObjectTypeReadState objState = (HollowObjectTypeReadState)typeState;
        
        for(int i=0;i<fieldPathIndexes.length;i++) {
            int curOrdinal = ordinal;
            HollowObjectTypeReadState curState = objState;
            
            for(int j=0;j<fieldPathIndexes[i].length - 1;j++) {
                curOrdinal = curState.readOrdinal(curOrdinal, fieldPathIndexes[i][j]);
                curState = (HollowObjectTypeReadState) curState.getSchema().getReferencedTypeState(fieldPathIndexes[i][j]);
            }
            
            if(!HollowReadFieldUtils.fieldValueEquals(curState, curOrdinal, fieldPathIndexes[i][fieldPathIndexes[i].length-1], key[i]))
                return false;
        }
        
        return true;
    }

}
