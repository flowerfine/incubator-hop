/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2018 by Hitachi Vantara : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.apache.hop.pipeline.steps.joinrows;

import org.apache.hop.core.CheckResult;
import org.apache.hop.core.CheckResultInterface;
import org.apache.hop.core.Condition;
import org.apache.hop.core.Const;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.exception.HopStepException;
import org.apache.hop.core.exception.HopXMLException;
import org.apache.hop.core.injection.Injection;
import org.apache.hop.core.injection.InjectionSupported;
import org.apache.hop.core.row.RowMetaInterface;
import org.apache.hop.core.variables.VariableSpace;
import org.apache.hop.core.xml.XMLHandler;
import org.apache.hop.i18n.BaseMessages;
import org.apache.hop.metastore.api.IMetaStore;
import org.apache.hop.pipeline.Pipeline;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.step.BaseStepMeta;
import org.apache.hop.pipeline.step.StepDataInterface;
import org.apache.hop.pipeline.step.StepInterface;
import org.apache.hop.pipeline.step.StepMeta;
import org.apache.hop.pipeline.step.StepMetaInterface;
import org.w3c.dom.Node;

import java.io.File;
import java.util.List;

/*
 * Created on 02-jun-2003
 *
 */
@InjectionSupported( localizationPrefix = "JoinRows.Injection." )
public class JoinRowsMeta extends BaseStepMeta implements StepMetaInterface {
  private static Class<?> PKG = JoinRowsMeta.class; // for i18n purposes, needed by Translator!!

  @Injection( name = "TEMP_DIR" )
  private String directory;
  @Injection( name = "TEMP_FILE_PREFIX" )
  private String prefix;
  @Injection( name = "MAX_CACHE_SIZE" )
  private int cacheSize;

  /**
   * Which step is providing the lookup data?
   */
  private StepMeta mainStep;

  /**
   * Which step is providing the lookup data?
   */
  @Injection( name = "MAIN_STEP" )
  private String mainStepname;

  /**
   * Optional condition to limit the join (where clause)
   */
  private Condition condition;

  /**
   * @return Returns the lookupFromStep.
   */
  public StepMeta getMainStep() {
    return mainStep;
  }

  /**
   * @param lookupFromStep The lookupFromStep to set.
   */
  public void setMainStep( StepMeta lookupFromStep ) {
    this.mainStep = lookupFromStep;
  }

  /**
   * @return Returns the lookupFromStepname.
   */
  public String getMainStepname() {
    return mainStepname;
  }

  /**
   * @param lookupFromStepname The lookupFromStepname to set.
   */
  public void setMainStepname( String lookupFromStepname ) {
    this.mainStepname = lookupFromStepname;
  }

  /**
   * @param cacheSize The cacheSize to set.
   */
  public void setCacheSize( int cacheSize ) {
    this.cacheSize = cacheSize;
  }

  /**
   * @return Returns the cacheSize.
   */
  public int getCacheSize() {
    return cacheSize;
  }

  /**
   * @return Returns the directory.
   */
  public String getDirectory() {
    return directory;
  }

  /**
   * @param directory The directory to set.
   */
  public void setDirectory( String directory ) {
    this.directory = directory;
  }

  /**
   * @return Returns the prefix.
   */
  public String getPrefix() {
    return prefix;
  }

  /**
   * @param prefix The prefix to set.
   */
  public void setPrefix( String prefix ) {
    this.prefix = prefix;
  }

  /**
   * @return Returns the condition.
   */
  public Condition getCondition() {
    return condition;
  }

  /**
   * @param condition The condition to set.
   */
  public void setCondition( Condition condition ) {
    this.condition = condition;
  }

  @Injection( name = "CONDITION" )
  public void setCondition( String conditionXML ) throws Exception {
    condition = new Condition( conditionXML );
  }

  public JoinRowsMeta() {
    super(); // allocate BaseStepMeta
    condition = new Condition();
  }

  @Override
  public void loadXML( Node stepnode, IMetaStore metaStore ) throws HopXMLException {
    readData( stepnode );
  }

  @Override
  public Object clone() {
    JoinRowsMeta retval = (JoinRowsMeta) super.clone();

    return retval;
  }

  private void readData( Node stepnode ) throws HopXMLException {
    try {
      directory = XMLHandler.getTagValue( stepnode, "directory" );
      prefix = XMLHandler.getTagValue( stepnode, "prefix" );
      cacheSize = Const.toInt( XMLHandler.getTagValue( stepnode, "cache_size" ), -1 );

      mainStepname = XMLHandler.getTagValue( stepnode, "main" );

      Node compare = XMLHandler.getSubNode( stepnode, "compare" );
      Node condnode = XMLHandler.getSubNode( compare, "condition" );

      // The new situation...
      if ( condnode != null ) {
        condition = new Condition( condnode );
      } else {
        condition = new Condition();
      }

    } catch ( Exception e ) {
      throw new HopXMLException( BaseMessages.getString(
        PKG, "JoinRowsMeta.Exception.UnableToReadStepInfoFromXML" ), e );
    }
  }

  @Override
  public void setDefault() {
    directory = "%%java.io.tmpdir%%";
    prefix = "out";
    cacheSize = 500;

    mainStepname = null;
  }

  @Override
  public String getXML() throws HopException {
    StringBuilder retval = new StringBuilder( 300 );

    retval.append( "      " ).append( XMLHandler.addTagValue( "directory", directory ) );
    retval.append( "      " ).append( XMLHandler.addTagValue( "prefix", prefix ) );
    retval.append( "      " ).append( XMLHandler.addTagValue( "cache_size", cacheSize ) );

    if ( mainStepname == null ) {
      mainStepname = getLookupStepname();
    }
    retval.append( "      " ).append( XMLHandler.addTagValue( "main", mainStepname ) );

    retval.append( "    <compare>" ).append( Const.CR );

    if ( condition != null ) {
      retval.append( condition.getXML() );
    }

    retval.append( "    </compare>" ).append( Const.CR );

    return retval.toString();
  }

  @Override
  public void getFields( RowMetaInterface rowMeta, String origin, RowMetaInterface[] info, StepMeta nextStep,
                         VariableSpace space, IMetaStore metaStore ) throws HopStepException {
    if ( space instanceof PipelineMeta ) {
      PipelineMeta pipelineMeta = (PipelineMeta) space;
      StepMeta[] steps = pipelineMeta.getPrevSteps( pipelineMeta.findStep( origin ) );
      StepMeta mainStep = pipelineMeta.findStep( getMainStepname() );
      rowMeta.clear();
      if ( mainStep != null ) {
        rowMeta.addRowMeta( pipelineMeta.getStepFields( mainStep ) );
      }
      for ( StepMeta step : steps ) {
        if ( mainStep == null || !step.equals( mainStep ) ) {
          rowMeta.addRowMeta( pipelineMeta.getStepFields( step ) );
        }
      }
    }
  }

  @Override
  public void check( List<CheckResultInterface> remarks, PipelineMeta pipelineMeta, StepMeta stepMeta,
                     RowMetaInterface prev, String[] input, String[] output, RowMetaInterface info, VariableSpace space,
                     IMetaStore metaStore ) {
    CheckResult cr;

    if ( prev != null && prev.size() > 0 ) {
      cr =
        new CheckResult( CheckResultInterface.TYPE_RESULT_OK, BaseMessages.getString(
          PKG, "JoinRowsMeta.CheckResult.StepReceivingDatas", prev.size() + "" ), stepMeta );
      remarks.add( cr );

      // Check the sort directory
      String realDirectory = pipelineMeta.environmentSubstitute( directory );
      File f = new File( realDirectory );
      if ( f.exists() ) {
        if ( f.isDirectory() ) {
          cr =
            new CheckResult(
              CheckResultInterface.TYPE_RESULT_OK, "["
              + realDirectory + BaseMessages.getString( PKG, "JoinRowsMeta.CheckResult.DirectoryExists" ),
              stepMeta );
          remarks.add( cr );
        } else {
          cr =
            new CheckResult( CheckResultInterface.TYPE_RESULT_ERROR, "["
              + realDirectory
              + BaseMessages.getString( PKG, "JoinRowsMeta.CheckResult.DirectoryExistsButNotValid" ), stepMeta );
          remarks.add( cr );
        }
      } else {
        cr =
          new CheckResult( CheckResultInterface.TYPE_RESULT_ERROR, BaseMessages.getString(
            PKG, "JoinRowsMeta.CheckResult.DirectoryDoesNotExist", realDirectory ), stepMeta );
        remarks.add( cr );
      }
    } else {
      cr =
        new CheckResult( CheckResultInterface.TYPE_RESULT_ERROR, BaseMessages.getString(
          PKG, "JoinRowsMeta.CheckResult.CouldNotFindFieldsFromPreviousSteps" ), stepMeta );
      remarks.add( cr );
    }

    // See if we have input streams leading to this step!
    if ( input.length > 0 ) {
      cr =
        new CheckResult( CheckResultInterface.TYPE_RESULT_OK, BaseMessages.getString(
          PKG, "JoinRowsMeta.CheckResult.StepReceivingInfoFromOtherSteps" ), stepMeta );
      remarks.add( cr );
    } else {
      cr =
        new CheckResult( CheckResultInterface.TYPE_RESULT_ERROR, BaseMessages.getString(
          PKG, "JoinRowsMeta.CheckResult.NoInputReceived" ), stepMeta );
      remarks.add( cr );
    }
  }

  public String getLookupStepname() {
    if ( mainStep != null && mainStep.getName() != null && mainStep.getName().length() > 0 ) {
      return mainStep.getName();
    }
    return null;
  }

  /**
   * @param steps optionally search the info step in a list of steps
   */
  @Override
  public void searchInfoAndTargetSteps( List<StepMeta> steps ) {
    mainStep = StepMeta.findStep( steps, mainStepname );
  }

  @Override
  public StepInterface getStep( StepMeta stepMeta, StepDataInterface stepDataInterface, int cnr,
                                PipelineMeta pipelineMeta, Pipeline pipeline ) {
    return new JoinRows( stepMeta, stepDataInterface, cnr, pipelineMeta, pipeline );
  }

  @Override
  public StepDataInterface getStepData() {
    return new JoinRowsData();
  }

  @Override
  public boolean excludeFromRowLayoutVerification() {
    return true;
  }

  @Override
  public boolean cleanAfterHopToRemove( StepMeta fromStep ) {
    boolean hasChanged = false;

    // If the hop we're removing comes from a Step that is being used as the main step for the Join, we have to clear
    // that reference
    if ( null != fromStep && fromStep.equals( getMainStep() ) ) {
      setMainStep( null );
      setMainStepname( null );
      hasChanged = true;
    }

    return hasChanged;
  }
}