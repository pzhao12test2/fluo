/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluo.core.impl;

import java.util.Collections;
import java.util.List;

import io.fluo.api.client.FluoClient;
import io.fluo.api.client.FluoFactory;
import io.fluo.api.client.LoaderExecutor;
import io.fluo.api.config.ObserverConfiguration;
import io.fluo.api.data.Bytes;
import io.fluo.api.data.Column;
import io.fluo.api.types.StringEncoder;
import io.fluo.api.types.TypeLayer;
import io.fluo.api.types.TypedLoader;
import io.fluo.api.types.TypedObserver;
import io.fluo.api.types.TypedSnapshot;
import io.fluo.api.types.TypedTransactionBase;
import io.fluo.core.ITBaseMini;
import org.apache.commons.configuration.Configuration;
import org.junit.Assert;
import org.junit.Test;

public class AppConfigIT extends ITBaseMini{

  @Override
  protected void setAppConfig(Configuration config){
    config.setProperty("myapp.sizeLimit", 50000);
  }
  
  @Override
  protected List<ObserverConfiguration> getObservers() {
    return Collections.singletonList(new ObserverConfiguration(TestObserver.class.getName()));
  }
  
  @Test
  public void testBasic(){
    Configuration uc = client.getAppConfiguration();
    Assert.assertEquals(50000, uc.getInt("myapp.sizeLimit"));
    uc.setProperty("myapp.sizeLimit", 3);
    uc = client.getAppConfiguration();
    Assert.assertEquals(50000, uc.getInt("myapp.sizeLimit"));
    
    //update shared config
    Configuration appConfig = config.getAppConfiguration();
    appConfig.clear();
    appConfig.setProperty("myapp.sizeLimit", 40000);
    appConfig.setProperty("myapp.timeLimit", 30000);
    FluoFactory.newAdmin(config).updateSharedConfig();;
    
    //set app config that differs from what was just put in zk
    appConfig.setProperty("myapp.sizeLimit", 6);
    appConfig.setProperty("myapp.timeLimit", 7);
    
    try(FluoClient client2 = FluoFactory.newClient(config)){
      uc = client2.getAppConfiguration();
      Assert.assertEquals(40000, uc.getInt("myapp.sizeLimit"));
      Assert.assertEquals(30000, uc.getInt("myapp.timeLimit"));
    }
    
  }
  
  
  
  public static class TestLoader extends TypedLoader {

    private String row;
    private int data;
    
    TestLoader(String row, int data){
      this.row = row;
      this.data = data;
    }
    
    @Override
    public void load(TypedTransactionBase tx, Context context) throws Exception {
      int limit = context.getAppConfiguration().getInt("myapp.sizeLimit");
      if(data < limit){
        tx.mutate().row(row).fam("data").qual("foo").set(data);
      }
    }
    
  }
  
  public static class TestObserver extends TypedObserver {

    private int limit;

    @Override
    public ObservedColumn getObservedColumn() {
      return new ObservedColumn(new Column("data","foo"), NotificationType.STRONG);
    }

    public void init(Context context){
      limit = context.getAppConfiguration().getInt("myapp.sizeLimit");
    }
    
    @Override
    public void process(TypedTransactionBase tx, Bytes row, Column col) {
      int d = tx.get().row(row).col(col).toInteger();
      if(2 * d < limit){
        tx.mutate().row(row).fam("data").qual("bar").set(2 * d);
      }
    }
    
  }
  
  
  @Test
  public void testLoaderAndObserver(){
    
    try (LoaderExecutor le = client.newLoaderExecutor()) {
      le.execute(new TestLoader("r1", 3));
      le.execute(new TestLoader("r2", 30000));
      le.execute(new TestLoader("r3", 60000));
    }
    
    TypeLayer tl = new TypeLayer(new StringEncoder());
    
    try(TypedSnapshot snapshot = tl.wrap(client.newSnapshot())){
      Assert.assertEquals(3, snapshot.get().row("r1").fam("data").qual("foo").toInteger(0));
      Assert.assertEquals(30000, snapshot.get().row("r2").fam("data").qual("foo").toInteger(0));
      Assert.assertEquals(0, snapshot.get().row("r3").fam("data").qual("foo").toInteger(0));
    }
    
    miniFluo.waitForObservers();
    
    try(TypedSnapshot snapshot = tl.wrap(client.newSnapshot())){
      Assert.assertEquals(6, snapshot.get().row("r1").fam("data").qual("bar").toInteger(0));
      Assert.assertEquals(0, snapshot.get().row("r2").fam("data").qual("bar").toInteger(0));
      Assert.assertEquals(0, snapshot.get().row("r3").fam("data").qual("bar").toInteger(0));
    }
    
  }
}
