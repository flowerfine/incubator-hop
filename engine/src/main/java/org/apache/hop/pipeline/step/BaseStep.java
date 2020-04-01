// CHECKSTYLE:FileLength:OFF
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

package org.apache.hop.pipeline.step;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.apache.commons.lang.StringUtils;
import org.apache.hop.core.BlockingRowSet;
import org.apache.hop.core.Const;
import org.apache.hop.core.ExtensionDataInterface;
import org.apache.hop.core.ResultFile;
import org.apache.hop.core.RowMetaAndData;
import org.apache.hop.core.RowSet;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.exception.HopRowException;
import org.apache.hop.core.exception.HopStepException;
import org.apache.hop.core.exception.HopValueException;
import org.apache.hop.core.logging.HopLogStore;
import org.apache.hop.core.logging.LogChannelInterface;
import org.apache.hop.core.logging.LogLevel;
import org.apache.hop.core.logging.LoggingObjectInterface;
import org.apache.hop.core.logging.LoggingObjectType;
import org.apache.hop.core.row.RowDataUtil;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.RowMetaInterface;
import org.apache.hop.core.row.ValueMetaInterface;
import org.apache.hop.core.row.value.ValueMetaDate;
import org.apache.hop.core.row.value.ValueMetaNumber;
import org.apache.hop.core.row.value.ValueMetaString;
import org.apache.hop.core.util.Utils;
import org.apache.hop.core.variables.VariableSpace;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.i18n.BaseMessages;
import org.apache.hop.metastore.api.IMetaStore;
import org.apache.hop.pipeline.BasePartitioner;
import org.apache.hop.pipeline.Pipeline;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.IEngineComponent;
import org.apache.hop.pipeline.step.BaseStepData.StepExecutionStatus;
import org.apache.hop.pipeline.steps.mapping.Mapping;
import org.apache.hop.pipeline.steps.mappinginput.MappingInput;
import org.apache.hop.pipeline.steps.mappingoutput.MappingOutput;
import org.apache.hop.www.SocketRepository;

import java.io.Closeable;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * This class can be extended for the actual row processing of the implemented step.
 * <p>
 * The implementing class can rely mostly on the base class, and has only three important methods it implements itself.
 * The three methods implement the step lifecycle during pipeline execution: initialization, row processing, and
 * clean-up.
 * <ul>
 * <li>Step Initialization<br/>
 * The init() method is called when a pipeline is preparing to start execution.
 * <p>
 * <pre>
 * <a href="#init(org.apache.hop.pipeline.step.StepMetaInterface,
 * org.apache.hop.pipeline.step.StepDataInterface)">public boolean init(...)</a>
 * </pre>
 * <p>
 * Every step is given the opportunity to do one-time initialization tasks like opening files or establishing database
 * connections. For any steps derived from BaseStep it is mandatory that super.init() is called to ensure correct
 * behavior. The method must return true in case the step initialized correctly, it must returned false if there was an
 * initialization error. PDI will abort the execution of a pipeline in case any step returns false upon
 * initialization.
 * <p></li>
 * <p>
 * <li>Row Processing<br/>
 * Once the pipeline starts execution it enters a tight loop calling processRow() on each step until the method
 * returns false. Each step typically reads a single row from the input stream, alters the row structure and fields and
 * passes the row on to next steps.
 * <p>
 * <pre>
 * <a href="#processRow(org.apache.hop.pipeline.step.StepMetaInterface,
 * org.apache.hop.pipeline.step.StepDataInterface)">public boolean processRow(...)</a>
 * </pre>
 * <p>
 * A typical implementation queries for incoming input rows by calling getRow(), which blocks and returns a row object
 * or null in case there is no more input. If there was an input row, the step does the necessary row processing and
 * calls putRow() to pass the row on to the next step. If there are no more rows, the step must call setOutputDone() and
 * return false.
 * <p>
 * Formally the method must conform to the following rules:
 * <ul>
 * <li>If the step is done processing all rows, the method must call setOutputDone() and return false</li>
 * <li>If the step is not done processing all rows, the method must return true. PDI will call processRow() again in
 * this case.</li>
 * </ul>
 * </li>
 * <p>
 * <li>Step Clean-Up<br/>
 * Once the pipeline is complete, PDI calls dispose() on all steps.
 * <p>
 * <pre>
 * <a href="#dispose(org.apache.hop.pipeline.step.StepMetaInterface,
 * org.apache.hop.pipeline.step.StepDataInterface)">public void dispose(...)</a>
 * </pre>
 * <p>
 * Steps are required to deallocate resources allocated during init() or subsequent row processing. This typically means
 * to clear all fields of the StepDataInterface object, and to ensure that all open files or connections are properly
 * closed. For any steps derived from BaseStep it is mandatory that super.dispose() is called to ensure correct
 * deallocation.
 * </ul>
 */
public class BaseStep implements VariableSpace, StepInterface, LoggingObjectInterface, ExtensionDataInterface, IEngineComponent {
  private static Class<?> PKG = BaseStep.class; // for i18n purposes, needed by Translator!!

  protected VariableSpace variables = new Variables();

  private PipelineMeta pipelineMeta;

  private StepMeta stepMeta;

  private String stepname;

  protected LogChannelInterface log;

  private String containerObjectId;

  private Pipeline pipeline;

  private final Object statusCountersLock = new Object();

  /**
   * nr of lines read from previous step(s)
   *
   * @deprecated use {@link #getLinesRead()}, {@link #incrementLinesRead()}, or {@link #decrementLinesRead()}
   */
  @Deprecated
  public long linesRead;

  /**
   * nr of lines written to next step(s)
   *
   * @deprecated use {@link #getLinesWritten()}, {@link #incrementLinesWritten()}, or {@link #decrementLinesWritten()}
   */
  @Deprecated
  public long linesWritten;

  /**
   * nr of lines read from file or database
   *
   * @deprecated use {@link #getLinesInput()} or {@link #incrementLinesInput()}
   */
  @Deprecated
  public long linesInput;

  /**
   * nr of lines written to file or database
   *
   * @deprecated use {@link #getLinesOutput()} or {@link #incrementLinesOutput()}
   */
  @Deprecated
  public long linesOutput;

  /**
   * nr of updates in a database table or file
   *
   * @deprecated use {@link #getLinesUpdated()} or {@link #incrementLinesUpdated()}
   */
  @Deprecated
  public long linesUpdated;

  /**
   * nr of lines skipped
   *
   * @deprecated use {@link #getLinesSkipped()} or {@link #incrementLinesSkipped()}
   */
  @Deprecated
  public long linesSkipped;

  /**
   * total sleep time in ns caused by an empty input buffer (previous step is slow)
   *
   * @deprecated use {@link #getLinesRejected()} or {@link #incrementLinesRejected()}
   */
  @Deprecated
  public long linesRejected;

  private boolean distributed;

  private String rowDistributionCode;

  private RowDistributionInterface rowDistribution;

  private long errors;

  private StepMeta[] nextSteps;

  private StepMeta[] prevSteps;

  private int currentInputRowSetNr, currentOutputRowSetNr;

  /**
   * The rowsets on the input, size() == nr of source steps
   */
  private List<RowSet> inputRowSets;

  private final ReentrantReadWriteLock inputRowSetsLock = new ReentrantReadWriteLock();

  /**
   * the rowsets on the output, size() == nr of target steps
   */
  private List<RowSet> outputRowSets;

  private final ReadWriteLock outputRowSetsLock = new ReentrantReadWriteLock();

  /**
   * the rowset for the error rows
   */
  private RowSet errorRowSet;

  private AtomicBoolean running;

  private AtomicBoolean stopped;

  protected AtomicBoolean safeStopped;

  private AtomicBoolean paused;

  private boolean init;

  /**
   * the copy number of this thread
   */
  private int copyNr;

  private Date start_time, stop_time;

  /**
   * if true then the row being processed is the first row
   */
  public boolean first;

  /**
   *
   */
  public boolean terminator;

  public List<Object[]> terminator_rows;

  private StepMetaInterface stepMetaInterface;

  private StepDataInterface stepDataInterface;

  /**
   * The list of RowListener interfaces
   */
  protected List<RowListener> rowListeners;

  /**
   * Map of files that are generated or used by this step. After execution, these can be added to result. The entry to
   * the map is the filename
   */
  private final Map<String, ResultFile> resultFiles;
  private final ReentrantReadWriteLock resultFilesLock;

  /**
   * This contains the first row received and will be the reference row. We used it to perform extra checking: see if we
   * don't get rows with "mixed" contents.
   */
  private RowMetaInterface inputReferenceRow;

  /**
   * This field tells the putRow() method that we are in partitioned mode
   */
  private boolean partitioned;

  /**
   * The partition ID at which this step copy runs, or null if this step is not running partitioned.
   */
  private String partitionID;

  /**
   * This field tells the putRow() method to re-partition the incoming data, See also
   * StepPartitioningMeta.PARTITIONING_METHOD_*
   */
  private int repartitioning;

  /**
   * The partitionID to rowset mapping
   */
  private Map<String, BlockingRowSet> partitionTargets;

  private RowMetaInterface inputRowMeta;

  /**
   * step partitioning information of the NEXT step
   */
  private StepPartitioningMeta nextStepPartitioningMeta;

  /**
   * The metadata information of the error output row. There is only one per step so we cache it
   */
  private RowMetaInterface errorRowMeta = null;

  private RowMetaInterface previewRowMeta;

  private boolean checkPipelineRunning;

  private static int NR_OF_ROWS_IN_BLOCK = 500;

  private int blockPointer;

  /**
   * A flag to determine whether or not we are doing local or clustered (remote) par
   */

  private boolean usingThreadPriorityManagment;

  private List<StepListener> stepListeners;

  /**
   * The socket repository to use when opening server side sockets in clustering mode
   */
  private SocketRepository socketRepository;

  /**
   * The upper buffer size boundary after which we manage the thread priority a little bit to prevent excessive locking
   */
  private int upperBufferBoundary;

  /**
   * The lower buffer size boundary after which we manage the thread priority a little bit to prevent excessive locking
   */
  private int lowerBufferBoundary;

  /**
   * maximum number of errors to allow
   */
  private Long maxErrors = -1L;

  /**
   * maximum percent of errors to allow
   */
  private int maxPercentErrors = -1;

  /**
   * minumum number of rows to process before using maxPercentErrors in calculation
   */
  private long minRowsForMaxErrorPercent = -1L;

  /**
   * set this flag to true to allow empty field names and types to output
   */
  private boolean allowEmptyFieldNamesAndTypes = false;

  /**
   * Keeps track of the number of rows read for input deadlock verification.
   */
  protected long deadLockCounter;

  /**
   * The metastore that the step uses to load external elements from
   */
  protected IMetaStore metaStore;

  protected Map<String, Object> extensionDataMap;

  /**
   * rowHandler handles getting/putting rows and putting errors.
   * Default implementation defers to corresponding methods in this class.
   */
  private RowHandler rowHandler;

  /**
   * This is the base step that forms that basis for all steps. You can derive from this class to implement your own
   * steps.
   *
   * @param stepMeta          The StepMeta object to run.
   * @param stepDataInterface the data object to store temporary data, database connections, caches, result sets,
   *                          hashtables etc.
   * @param copyNr            The copynumber for this step.
   * @param pipelineMeta         The PipelineMeta of which the step stepMeta is part of.
   * @param pipeline             The (running) pipeline to obtain information shared among the steps.
   */
  public BaseStep( StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, PipelineMeta pipelineMeta,
                   Pipeline pipeline ) {
    this.stepMeta = stepMeta;
    this.stepDataInterface = stepDataInterface;
    this.copyNr = copyNr;
    this.pipelineMeta = pipelineMeta;
    this.pipeline = pipeline;
    this.stepname = stepMeta.getName();
    this.socketRepository = pipeline.getSocketRepository();

    // Set the name of the thread
    if ( stepMeta.getName() == null ) {
      throw new RuntimeException( "A step in pipeline ["
        + pipelineMeta.toString() + "] doesn't have a name.  A step should always have a name to identify it by." );
    }

    log = HopLogStore.getLogChannelInterfaceFactory().create( this, pipeline );

    first = true;

    running = new AtomicBoolean( false );
    stopped = new AtomicBoolean( false );
    safeStopped = new AtomicBoolean( false );
    paused = new AtomicBoolean( false );

    init = false;

    synchronized ( statusCountersLock ) {
      linesRead = 0L; // new AtomicLong(0L); // Keep some statistics!
      linesWritten = 0L; // new AtomicLong(0L);
      linesUpdated = 0L; // new AtomicLong(0L);
      linesSkipped = 0L; // new AtomicLong(0L);
      linesRejected = 0L; // new AtomicLong(0L);
      linesInput = 0L; // new AtomicLong(0L);
      linesOutput = 0L; // new AtomicLong(0L);
    }

    inputRowSets = null;
    outputRowSets = null;
    nextSteps = null;

    terminator = stepMeta.hasTerminator();
    if ( terminator ) {
      terminator_rows = new ArrayList<>();
    } else {
      terminator_rows = null;
    }

    // debug="-";

    start_time = null;
    stop_time = null;

    distributed = stepMeta.isDistributes();
    rowDistribution = stepMeta.getRowDistribution();

    if ( distributed ) {
      if ( rowDistribution != null ) {
        if ( log.isDetailed() ) {
          logDetailed( BaseMessages.getString(
            PKG, "BaseStep.Log.CustomRowDistributionActivated", rowDistributionCode ) );
        }
      } else {
        if ( log.isDetailed() ) {
          logDetailed( BaseMessages.getString( PKG, "BaseStep.Log.DistributionActivated" ) );
        }
      }
    } else {
      if ( log.isDetailed() ) {
        logDetailed( BaseMessages.getString( PKG, "BaseStep.Log.DistributionDeactivated" ) );
      }
    }

    rowListeners = new CopyOnWriteArrayList<RowListener>();
    resultFiles = new HashMap<String, ResultFile>();
    resultFilesLock = new ReentrantReadWriteLock();

    repartitioning = StepPartitioningMeta.PARTITIONING_METHOD_NONE;
    partitionTargets = new Hashtable<String, BlockingRowSet>();

    extensionDataMap = new HashMap<>();

    // tuning parameters
    // putTimeOut = 10; //s
    // getTimeOut = 500; //s
    // timeUnit = TimeUnit.MILLISECONDS;
    // the smaller singleWaitTime, the faster the program run but cost CPU
    // singleWaitTime = 1; //ms
    // maxPutWaitCount = putTimeOut*1000/singleWaitTime;
    // maxGetWaitCount = getTimeOut*1000/singleWaitTime;

    // worker = Executors.newFixedThreadPool(10);
    checkPipelineRunning = false;

    blockPointer = 0;

    stepListeners = Collections.synchronizedList( new ArrayList<StepListener>() );

    dispatch();

    upperBufferBoundary = (int) ( pipeline.getRowSetSize() * 0.99 );
    lowerBufferBoundary = (int) ( pipeline.getRowSetSize() * 0.01 );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#init(org.apache.hop.pipeline.step.StepMetaInterface,
   * org.apache.hop.pipeline.step.StepDataInterface)
   */
  @Override
  public boolean init( StepMetaInterface smi, StepDataInterface sdi ) {
    sdi.setStatus( StepExecutionStatus.STATUS_INIT );

    if ( stepMeta.isPartitioned() ) {
      // This is a locally partitioned step...
      //
      int partitionNr = copyNr;
      String partitionNrString = new DecimalFormat( "000" ).format( partitionNr );
      setVariable( Const.INTERNAL_VARIABLE_STEP_PARTITION_NR, partitionNrString );
      final List<String> partitionIDList = stepMeta.getStepPartitioningMeta().getPartitionSchema().calculatePartitionIDs();

      if ( partitionIDList.size() > 0 ) {
        String partitionID = partitionIDList.get( partitionNr );
        setVariable( Const.INTERNAL_VARIABLE_STEP_PARTITION_ID, partitionID );
      } else {
        logError( BaseMessages.getString( PKG, "BaseStep.Log.UnableToRetrievePartitionId",
          stepMeta.getStepPartitioningMeta().getPartitionSchema().getName() ) );
        return false;
      }
    } else if ( !Utils.isEmpty( partitionID ) ) {
      setVariable( Const.INTERNAL_VARIABLE_STEP_PARTITION_ID, partitionID );
    }

    setVariable( Const.INTERNAL_VARIABLE_STEP_COPYNR, Integer.toString( copyNr ) );

    // BACKLOG-18004
    allowEmptyFieldNamesAndTypes = Boolean.parseBoolean( System.getProperties().getProperty( Const.HOP_ALLOW_EMPTY_FIELD_NAMES_AND_TYPES, "false" ) );


    // Getting ans setting the error handling values
    // first, get the step meta
    StepErrorMeta stepErrorMeta = stepMeta.getStepErrorMeta();
    if ( stepErrorMeta != null ) {

      // do an environment substitute for stepErrorMeta.getMaxErrors(), stepErrorMeta.getMinPercentRows()
      // and stepErrorMeta.getMaxPercentErrors()
      // Catch NumberFormatException since the user can enter anything in the dialog- the value
      // they enter must be a number or a variable set to a number

      // We will use a boolean to indicate failure so that we can log all errors - not just the first one caught
      boolean envSubFailed = false;
      try {
        maxErrors =
          ( !Utils.isEmpty( stepErrorMeta.getMaxErrors() ) ? Long.valueOf( pipeline
            .environmentSubstitute( stepErrorMeta.getMaxErrors() ) ) : -1L );
      } catch ( NumberFormatException nfe ) {
        log.logError( BaseMessages.getString( PKG, "BaseStep.Log.NumberFormatException", BaseMessages.getString(
          PKG, "BaseStep.Property.MaxErrors.Name" ), this.stepname, ( stepErrorMeta.getMaxErrors() != null
          ? stepErrorMeta.getMaxErrors() : "" ) ) );
        envSubFailed = true;
      }

      try {
        minRowsForMaxErrorPercent =
          ( !Utils.isEmpty( stepErrorMeta.getMinPercentRows() ) ? Long.valueOf( pipeline
            .environmentSubstitute( stepErrorMeta.getMinPercentRows() ) ) : -1L );
      } catch ( NumberFormatException nfe ) {
        log.logError( BaseMessages.getString( PKG, "BaseStep.Log.NumberFormatException", BaseMessages.getString(
          PKG, "BaseStep.Property.MinRowsForErrorsPercentCalc.Name" ), this.stepname, ( stepErrorMeta
          .getMinPercentRows() != null ? stepErrorMeta.getMinPercentRows() : "" ) ) );
        envSubFailed = true;
      }

      try {
        maxPercentErrors =
          ( !Utils.isEmpty( stepErrorMeta.getMaxPercentErrors() ) ? Integer.valueOf( pipeline
            .environmentSubstitute( stepErrorMeta.getMaxPercentErrors() ) ) : -1 );
      } catch ( NumberFormatException nfe ) {
        log.logError( BaseMessages.getString(
          PKG, "BaseStep.Log.NumberFormatException", BaseMessages.getString(
            PKG, "BaseStep.Property.MaxPercentErrors.Name" ), this.stepname, ( stepErrorMeta
            .getMaxPercentErrors() != null ? stepErrorMeta.getMaxPercentErrors() : "" ) ) );
        envSubFailed = true;
      }

      // if we failed and environment subsutitue
      if ( envSubFailed ) {
        return false;
      }
    }

    return true;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#dispose(org.apache.hop.pipeline.step.StepMetaInterface,
   * org.apache.hop.pipeline.step.StepDataInterface)
   */
  @Override
  public void dispose( StepMetaInterface smi, StepDataInterface sdi ) {
    sdi.setStatus( StepExecutionStatus.STATUS_DISPOSED );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#cleanup()
   */
  @Override
  public void cleanup() {
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#getProcessed()
   */
  @Override
  public long getProcessed() {
    if ( getLinesRead() > getLinesWritten() ) {
      return getLinesRead();
    } else {
      return getLinesWritten();
    }
  }

  /**
   * Sets the copy.
   *
   * @param cop the new copy
   */
  public void setCopy( int cop ) {
    copyNr = cop;
  }

  /**
   * @return The steps copy number (default 0)
   */
  @Override
  public int getCopy() {
    return copyNr;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#getErrors()
   */
  @Override
  public long getErrors() {
    return errors;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#setErrors(long)
   */
  @Override
  public void setErrors( long e ) {
    errors = e;
  }

  /**
   * @return Returns the number of lines read from previous steps
   */
  @Override
  public long getLinesRead() {
    synchronized ( statusCountersLock ) {
      return linesRead;
    }
  }

  /**
   * Increments the number of lines read from previous steps by one
   *
   * @return Returns the new value
   */
  public long incrementLinesRead() {
    synchronized ( statusCountersLock ) {
      return ++linesRead;
    }
  }

  /**
   * Decrements the number of lines read from previous steps by one
   *
   * @return Returns the new value
   */
  public long decrementLinesRead() {
    synchronized ( statusCountersLock ) {
      return --linesRead;
    }
  }

  /**
   * @param newLinesReadValue the new number of lines read from previous steps
   */
  public void setLinesRead( long newLinesReadValue ) {
    synchronized ( statusCountersLock ) {
      linesRead = newLinesReadValue;
    }
  }

  /**
   * @return Returns the number of lines read from an input source: database, file, socket, etc.
   */
  @Override
  public long getLinesInput() {
    synchronized ( statusCountersLock ) {
      return linesInput;
    }
  }

  /**
   * Increments the number of lines read from an input source: database, file, socket, etc.
   *
   * @return the new incremented value
   */
  public long incrementLinesInput() {
    synchronized ( statusCountersLock ) {
      return ++linesInput;
    }
  }

  /**
   * @param newLinesInputValue the new number of lines read from an input source: database, file, socket, etc.
   */
  public void setLinesInput( long newLinesInputValue ) {
    synchronized ( statusCountersLock ) {
      linesInput = newLinesInputValue;
    }
  }

  /**
   * @return Returns the number of lines written to an output target: database, file, socket, etc.
   */
  @Override
  public long getLinesOutput() {
    synchronized ( statusCountersLock ) {
      return linesOutput;
    }
  }

  /**
   * Increments the number of lines written to an output target: database, file, socket, etc.
   *
   * @return the new incremented value
   */
  public long incrementLinesOutput() {
    synchronized ( statusCountersLock ) {
      return ++linesOutput;
    }
  }

  /**
   * @param newLinesOutputValue the new number of lines written to an output target: database, file, socket, etc.
   */
  public void setLinesOutput( long newLinesOutputValue ) {
    synchronized ( statusCountersLock ) {
      linesOutput = newLinesOutputValue;
    }
  }

  /**
   * @return Returns the linesWritten.
   */
  @Override
  public long getLinesWritten() {
    synchronized ( statusCountersLock ) {
      return linesWritten;
    }
  }

  /**
   * Increments the number of lines written to next steps by one
   *
   * @return Returns the new value
   */
  public long incrementLinesWritten() {
    synchronized ( statusCountersLock ) {
      return ++linesWritten;
    }
  }

  /**
   * Decrements the number of lines written to next steps by one
   *
   * @return Returns the new value
   */
  public long decrementLinesWritten() {
    synchronized ( statusCountersLock ) {
      return --linesWritten;
    }
  }

  /**
   * @param newLinesWrittenValue the new number of lines written to next steps
   */
  public void setLinesWritten( long newLinesWrittenValue ) {
    synchronized ( statusCountersLock ) {
      linesWritten = newLinesWrittenValue;
    }
  }

  /**
   * @return Returns the number of lines updated in an output target: database, file, socket, etc.
   */
  @Override
  public long getLinesUpdated() {
    synchronized ( statusCountersLock ) {
      return linesUpdated;
    }
  }

  /**
   * Increments the number of lines updated in an output target: database, file, socket, etc.
   *
   * @return the new incremented value
   */
  public long incrementLinesUpdated() {
    synchronized ( statusCountersLock ) {
      return ++linesUpdated;
    }
  }

  /**
   * @param newLinesUpdatedValue the new number of lines updated in an output target: database, file, socket, etc.
   */
  public void setLinesUpdated( long newLinesUpdatedValue ) {
    synchronized ( statusCountersLock ) {
      linesUpdated = newLinesUpdatedValue;
    }
  }

  /**
   * @return the number of lines rejected to an error handling step
   */
  @Override
  public long getLinesRejected() {
    synchronized ( statusCountersLock ) {
      return linesRejected;
    }
  }

  /**
   * Increments the number of lines rejected to an error handling step
   *
   * @return the new incremented value
   */
  public long incrementLinesRejected() {
    synchronized ( statusCountersLock ) {
      return ++linesRejected;
    }
  }

  /**
   * @param newLinesRejectedValue lines number of lines rejected to an error handling step
   */
  @Override
  public void setLinesRejected( long newLinesRejectedValue ) {
    synchronized ( statusCountersLock ) {
      linesRejected = newLinesRejectedValue;
    }
  }

  /**
   * @return the number of lines skipped
   */
  public long getLinesSkipped() {
    synchronized ( statusCountersLock ) {
      return linesSkipped;
    }
  }

  /**
   * Increments the number of lines skipped
   *
   * @return the new incremented value
   */
  public long incrementLinesSkipped() {
    synchronized ( statusCountersLock ) {
      return ++linesSkipped;
    }
  }

  /**
   * @param newLinesSkippedValue lines number of lines skipped
   */
  public void setLinesSkipped( long newLinesSkippedValue ) {
    synchronized ( statusCountersLock ) {
      linesSkipped = newLinesSkippedValue;
    }
  }

  @Override public boolean isSelected() {
    return stepMeta != null && stepMeta.isSelected();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#getStepname()
   */
  @Override
  public String getStepname() {
    return stepname;
  }

  /**
   * Sets the stepname.
   *
   * @param stepname the new stepname
   */
  public void setStepname( String stepname ) {
    this.stepname = stepname;
  }

  /**
   * Gets the dispatcher.
   *
   * @return the dispatcher
   */
  public Pipeline getDispatcher() {
    return pipeline;
  }

  /**
   * Gets the status description.
   *
   * @return the status description
   */
  public String getStatusDescription() {
    return getStatus().getDescription();
  }

  /**
   * @return Returns the stepMetaInterface.
   */
  public StepMetaInterface getStepMetaInterface() {
    return stepMetaInterface;
  }

  /**
   * @param stepMetaInterface The stepMetaInterface to set.
   */
  public void setStepMetaInterface( StepMetaInterface stepMetaInterface ) {
    this.stepMetaInterface = stepMetaInterface;
  }

  /**
   * @return Returns the stepDataInterface.
   */
  public StepDataInterface getStepDataInterface() {
    return stepDataInterface;
  }

  /**
   * @param stepDataInterface The stepDataInterface to set.
   */
  public void setStepDataInterface( StepDataInterface stepDataInterface ) {
    this.stepDataInterface = stepDataInterface;
  }

  /**
   * @return Returns the stepMeta.
   */
  @Override
  public StepMeta getStepMeta() {
    return stepMeta;
  }

  /**
   * @param stepMeta The stepMeta to set.
   */
  public void setStepMeta( StepMeta stepMeta ) {
    this.stepMeta = stepMeta;
  }

  /**
   * @return Returns the pipelineMeta.
   */
  public PipelineMeta getPipelineMeta() {
    return pipelineMeta;
  }

  /**
   * @param pipelineMeta The pipelineMeta to set.
   */
  public void setPipelineMeta( PipelineMeta pipelineMeta ) {
    this.pipelineMeta = pipelineMeta;
  }

  /**
   * @return Returns the pipeline.
   */
  @Override
  public Pipeline getPipeline() {
    return pipeline;
  }


  /**
   * putRow is used to copy a row, to the alternate rowset(s) This should get priority over everything else!
   * (synchronized) If distribute is true, a row is copied only once to the output rowsets, otherwise copies are sent to
   * each rowset!
   *
   * @param row The row to put to the destination rowset(s).
   * @throws HopStepException
   */
  @Override
  public void putRow( RowMetaInterface rowMeta, Object[] row ) throws HopStepException {
    if ( rowMeta != null ) {
      if ( !allowEmptyFieldNamesAndTypes ) {
        // check row meta for empty field name (BACKLOG-18004)
        for ( ValueMetaInterface vmi : rowMeta.getValueMetaList() ) {
          if ( StringUtils.isBlank( vmi.getName() ) ) {
            throw new HopStepException( "Please set a field name for all field(s) that have 'null'." );
          }
          if ( vmi.getType() <= 0 ) {
            throw new HopStepException( "Please set a value for the missing field(s) type." );
          }
        }
      }
    }
    getRowHandler().putRow( rowMeta, row );
  }

  private void handlePutRow( RowMetaInterface rowMeta, Object[] row ) throws HopStepException {
    // Are we pausing the step? If so, stall forever...
    //
    while ( paused.get() && !stopped.get() ) {
      try {
        Thread.sleep( 1 );
      } catch ( InterruptedException e ) {
        throw new HopStepException( e );
      }
    }

    // Right after the pause loop we have to check if this thread is stopped or
    // not.
    //
    if ( stopped.get() && !safeStopped.get() ) {
      if ( log.isDebug() ) {
        logDebug( BaseMessages.getString( PKG, "BaseStep.Log.StopPuttingARow" ) );
      }
      stopAll();
      return;
    }

    // Have all threads started?
    // Are we running yet? If not, wait a bit until all threads have been
    // started.
    //
    if ( this.checkPipelineRunning == false ) {
      while ( !pipeline.isRunning() && !stopped.get() ) {
        try {
          Thread.sleep( 1 );
        } catch ( InterruptedException e ) {
          // Ignore
        }
      }
      this.checkPipelineRunning = true;
    }

    // call all row listeners...
    //
    for ( RowListener listener : rowListeners ) {
      listener.rowWrittenEvent( rowMeta, row );
    }

    // Keep adding to terminator_rows buffer...
    //
    if ( terminator && terminator_rows != null ) {
      try {
        terminator_rows.add( rowMeta.cloneRow( row ) );
      } catch ( HopValueException e ) {
        throw new HopStepException( "Unable to clone row while adding rows to the terminator rows.", e );
      }
    }

    outputRowSetsLock.readLock().lock();
    try {
      if ( outputRowSets.isEmpty() ) {
        // No more output rowsets!
        // Still update the nr of lines written.
        //
        incrementLinesWritten();

        return; // we're done here!
      }

      // Repartitioning happens when the current step is not partitioned, but the next one is.
      // That means we need to look up the partitioning information in the next step..
      // If there are multiple steps, we need to look at the first (they should be all the same)
      //
      switch ( repartitioning ) {
        case StepPartitioningMeta.PARTITIONING_METHOD_NONE:
          noPartitioning( rowMeta, row );
          break;

        case StepPartitioningMeta.PARTITIONING_METHOD_SPECIAL:
          specialPartitioning( rowMeta, row );
          break;
        case StepPartitioningMeta.PARTITIONING_METHOD_MIRROR:
          mirrorPartitioning( rowMeta, row );
          break;
        default:
          throw new HopStepException( "Internal error: invalid repartitioning type: " + repartitioning );
      }
    } finally {
      outputRowSetsLock.readLock().unlock();
    }
  }

  /**
   * Copy always to all target steps/copies
   */
  private void mirrorPartitioning( RowMetaInterface rowMeta, Object[] row ) {
    for ( RowSet rowSet : outputRowSets ) {
      putRowToRowSet( rowSet, rowMeta, row );
    }
  }

  private void specialPartitioning( RowMetaInterface rowMeta, Object[] row ) throws HopStepException {
    if ( nextStepPartitioningMeta == null ) {
      // Look up the partitioning of the next step.
      // This is the case for non-clustered partitioning...
      //
      List<StepMeta> nextSteps = pipelineMeta.findNextSteps( stepMeta );
      if ( nextSteps.size() > 0 ) {
        nextStepPartitioningMeta = nextSteps.get( 0 ).getStepPartitioningMeta();
      }

      // TODO: throw exception if we're not partitioning yet.
      // For now it throws a NP Exception.
    }

    int partitionNr;
    try {
      partitionNr = nextStepPartitioningMeta.getPartition( rowMeta, row );
    } catch ( HopException e ) {
      throw new HopStepException(
        "Unable to convert a value to integer while calculating the partition number", e );
    }

    RowSet selectedRowSet = null;


    // Local partitioning...
    // Put the row forward to the next step according to the partition rule.
    //

    // Count of partitioned row at one step
    int partCount = ( (BasePartitioner) nextStepPartitioningMeta.getPartitioner() ).getNrPartitions();

    for ( int i = 0; i < nextSteps.length; i++ ) {

      selectedRowSet = outputRowSets.get( partitionNr + i * partCount );

      if ( selectedRowSet == null ) {
        logBasic( BaseMessages.getString( PKG, "BaseStep.TargetRowsetIsNotAvailable", partitionNr ) );
      } else {

        // Wait
        putRowToRowSet( selectedRowSet, rowMeta, row );
        incrementLinesWritten();

        if ( log.isRowLevel() ) {
          try {
            logRowlevel( BaseMessages.getString( PKG, "BaseStep.PartitionedToRow", partitionNr,
              selectedRowSet, rowMeta.getString( row ) ) );
          } catch ( HopValueException e ) {
            throw new HopStepException( e );
          }
        }
      }
    }

  }

  private void noPartitioning( RowMetaInterface rowMeta, Object[] row ) throws HopStepException {
    if ( distributed ) {
      if ( rowDistribution != null ) {
        // Plugin defined row distribution!
        //
        rowDistribution.distributeRow( rowMeta, row, this );
        incrementLinesWritten();
      } else {
        // ROUND ROBIN DISTRIBUTION:
        // --------------------------
        // Copy the row to the "next" output rowset.
        // We keep the next one in out_handling
        //
        RowSet rs = outputRowSets.get( currentOutputRowSetNr );

        // To reduce stress on the locking system we are NOT going to allow
        // the buffer to grow to its full capacity.
        //
        if ( isUsingThreadPriorityManagment() && !rs.isDone() && rs.size() >= upperBufferBoundary && !isStopped() ) {
          try {
            Thread.sleep( 0, 1 );
          } catch ( InterruptedException e ) {
            // Ignore sleep interruption exception
          }
        }

        // Loop until we find room in the target rowset
        //
        putRowToRowSet( rs, rowMeta, row );
        incrementLinesWritten();

        // Now determine the next output rowset!
        // Only if we have more then one output...
        //
        if ( outputRowSets.size() > 1 ) {
          currentOutputRowSetNr++;
          if ( currentOutputRowSetNr >= outputRowSets.size() ) {
            currentOutputRowSetNr = 0;
          }
        }
      }
    } else {

      // Copy the row to all output rowsets
      //

      // Copy to the row in the other output rowsets...
      for ( int i = 1; i < outputRowSets.size(); i++ ) { // start at 1

        RowSet rs = outputRowSets.get( i );

        // To reduce stress on the locking system we are NOT going to allow
        // the buffer to grow to its full capacity.
        //
        if ( isUsingThreadPriorityManagment() && !rs.isDone() && rs.size() >= upperBufferBoundary && !isStopped() ) {
          try {
            Thread.sleep( 0, 1 );
          } catch ( InterruptedException e ) {
            // Ignore sleep interruption exception
          }
        }

        try {
          // Loop until we find room in the target rowset
          //
          putRowToRowSet( rs, rowMeta, rowMeta.cloneRow( row ) );
          incrementLinesWritten();
        } catch ( HopValueException e ) {
          throw new HopStepException( "Unable to clone row while copying rows to multiple target steps", e );
        }
      }

      // set row in first output rowset
      //
      RowSet rs = outputRowSets.get( 0 );
      putRowToRowSet( rs, rowMeta, row );
      incrementLinesWritten();
    }
  }

  private void putRowToRowSet( RowSet rs, RowMetaInterface rowMeta, Object[] row ) {
    RowMetaInterface toBeSent;
    RowMetaInterface metaFromRs = rs.getRowMeta();
    if ( metaFromRs == null ) {
      // RowSet is not initialised so far
      toBeSent = rowMeta.clone();
    } else {
      // use the existing
      toBeSent = metaFromRs;
    }

    while ( !rs.putRow( toBeSent, row ) ) {
      if ( isStopped() && !safeStopped.get() ) {
        return;
      }
    }
  }

  /**
   * putRowTo is used to put a row in a certain specific RowSet.
   *
   * @param rowMeta The row meta-data to put to the destination RowSet.
   * @param row     the data to put in the RowSet
   * @param rowSet  the RoWset to put the row into.
   * @throws HopStepException In case something unexpected goes wrong
   */
  public void putRowTo( RowMetaInterface rowMeta, Object[] row, RowSet rowSet ) throws HopStepException {
    getRowHandler().putRowTo( rowMeta, row, rowSet );
  }

  public void handlePutRowTo( RowMetaInterface rowMeta, Object[] row, RowSet rowSet ) throws HopStepException {

    // Are we pausing the step? If so, stall forever...
    //
    while ( paused.get() && !stopped.get() ) {
      try {
        Thread.sleep( 1 );
      } catch ( InterruptedException e ) {
        throw new HopStepException( e );
      }
    }

    // call all row listeners...
    //
    for ( RowListener listener : rowListeners ) {
      listener.rowWrittenEvent( rowMeta, row );
    }

    // Keep adding to terminator_rows buffer...
    if ( terminator && terminator_rows != null ) {
      try {
        terminator_rows.add( rowMeta.cloneRow( row ) );
      } catch ( HopValueException e ) {
        throw new HopStepException( "Unable to clone row while adding rows to the terminator buffer", e );
      }
    }

    if ( stopped.get() ) {
      if ( log.isDebug() ) {
        logDebug( BaseMessages.getString( PKG, "BaseStep.Log.StopPuttingARow" ) );
      }
      stopAll();
      return;
    }

    // Don't distribute or anything, only go to this rowset!
    //
    while ( !rowSet.putRow( rowMeta, row ) ) {
      if ( isStopped() ) {
        break;
      }
    }
    incrementLinesWritten();
  }

  /**
   * Put error.
   *
   * @param rowMeta           the row meta
   * @param row               the row
   * @param nrErrors          the nr errors
   * @param errorDescriptions the error descriptions
   * @param fieldNames        the field names
   * @param errorCodes        the error codes
   * @throws HopStepException the kettle step exception
   */
  public void putError( RowMetaInterface rowMeta, Object[] row, long nrErrors, String errorDescriptions,
                        String fieldNames, String errorCodes ) throws HopStepException {
    getRowHandler().putError( rowMeta, row, nrErrors, errorDescriptions, fieldNames, errorCodes );
  }


  private void handlePutError( RowMetaInterface rowMeta, Object[] row, long nrErrors, String errorDescriptions,
                               String fieldNames, String errorCodes ) throws HopStepException {
    if ( pipeline.isSafeModeEnabled() ) {
      if ( rowMeta.size() > row.length ) {
        throw new HopStepException( BaseMessages.getString(
          PKG, "BaseStep.Exception.MetadataDoesntMatchDataRowSize", Integer.toString( rowMeta.size() ), Integer
            .toString( row != null ? row.length : 0 ) ) );
      }
    }

    StepErrorMeta stepErrorMeta = stepMeta.getStepErrorMeta();

    if ( errorRowMeta == null ) {
      errorRowMeta = rowMeta.clone();

      RowMetaInterface add = stepErrorMeta.getErrorRowMeta( nrErrors, errorDescriptions, fieldNames, errorCodes );
      errorRowMeta.addRowMeta( add );
    }

    Object[] errorRowData = RowDataUtil.allocateRowData( errorRowMeta.size() );
    if ( row != null ) {
      System.arraycopy( row, 0, errorRowData, 0, rowMeta.size() );
    }

    // Also add the error fields...
    stepErrorMeta.addErrorRowData(
      errorRowData, rowMeta.size(), nrErrors, errorDescriptions, fieldNames, errorCodes );

    // call all row listeners...
    for ( RowListener listener : rowListeners ) {
      listener.errorRowWrittenEvent( rowMeta, row );
    }

    if ( errorRowSet != null ) {
      while ( !errorRowSet.putRow( errorRowMeta, errorRowData ) ) {
        if ( isStopped() ) {
          break;
        }
      }
      incrementLinesRejected();
    }

    verifyRejectionRates();
  }

  /**
   * Verify rejection rates.
   */
  private void verifyRejectionRates() {
    StepErrorMeta stepErrorMeta = stepMeta.getStepErrorMeta();
    if ( stepErrorMeta == null ) {
      return; // nothing to verify.
    }

    // Was this one error too much?
    if ( maxErrors > 0 && getLinesRejected() > maxErrors ) {
      logError( BaseMessages.getString( PKG, "BaseStep.Log.TooManyRejectedRows", Long.toString( maxErrors ), Long
        .toString( getLinesRejected() ) ) );
      setErrors( 1L );
      stopAll();
    }

    if ( maxPercentErrors > 0
      && getLinesRejected() > 0
      && ( minRowsForMaxErrorPercent <= 0 || getLinesRead() >= minRowsForMaxErrorPercent ) ) {
      int pct =
        (int) Math.ceil( 100 * (double) getLinesRejected() / getLinesRead() ); // additional conversion for PDI-10210
      if ( pct > maxPercentErrors ) {
        logError( BaseMessages.getString(
          PKG, "BaseStep.Log.MaxPercentageRejectedReached", Integer.toString( pct ), Long
            .toString( getLinesRejected() ), Long.toString( getLinesRead() ) ) );
        setErrors( 1L );
        stopAll();
      }
    }
  }

  /**
   * Current input stream.
   *
   * @return the row set
   */
  @VisibleForTesting
  RowSet currentInputStream() {
    inputRowSetsLock.readLock().lock();
    try {
      return inputRowSets.get( currentInputRowSetNr );
    } finally {
      inputRowSetsLock.readLock().unlock();
    }
  }

  /**
   * Find the next not-finished input-stream... in_handling says which one...
   */
  private void nextInputStream() {
    blockPointer = 0;

    int streams = inputRowSets.size();

    // No more streams left: exit!
    if ( streams == 0 ) {
      return;
    }

    // Just the one rowSet (common case)
    if ( streams == 1 ) {
      currentInputRowSetNr = 0;
    }

    // If we have some left: take the next!
    currentInputRowSetNr++;
    if ( currentInputRowSetNr >= streams ) {
      currentInputRowSetNr = 0;
    }
  }

  /**
   * Wait until the pipeline is completely running and all threads have been started.
   */
  protected void waitUntilPipelineIsStarted() {
    // Have all threads started?
    // Are we running yet? If not, wait a bit until all threads have been
    // started.
    //
    if ( this.checkPipelineRunning == false ) {
      while ( !pipeline.isRunning() && !stopped.get() ) {
        try {
          Thread.sleep( 1 );
        } catch ( InterruptedException e ) {
          // Ignore sleep interruption exception
        }
      }
      this.checkPipelineRunning = true;
    }
  }


  /**
   * In case of getRow, we receive data from previous steps through the input rowset. In case we split the stream, we
   * have to copy the data to the alternate splits: rowsets 1 through n.
   */
  @Override
  public Object[] getRow() throws HopException {
    return getRowHandler().getRow();
  }


  private Object[] handleGetRow() throws HopException {

    // Are we pausing the step? If so, stall forever...
    //
    while ( paused.get() && !stopped.get() ) {
      try {
        Thread.sleep( 100 );
      } catch ( InterruptedException e ) {
        throw new HopStepException( e );
      }
    }

    if ( stopped.get() ) {
      if ( log.isDebug() ) {
        logDebug( BaseMessages.getString( PKG, "BaseStep.Log.StopLookingForMoreRows" ) );
      }
      stopAll();
      return null;
    }

    // Small startup check
    //
    waitUntilPipelineIsStarted();

    RowSet inputRowSet = null;
    Object[] row = null;

    inputRowSetsLock.readLock().lock();
    try {

      // If everything is finished, we can stop immediately!
      //
      if ( inputRowSets.isEmpty() ) {
        return null;
      }

      // Do we need to switch to the next input stream?
      if ( blockPointer >= NR_OF_ROWS_IN_BLOCK ) {

        // Take a peek at the next input stream.
        // If there is no data, process another NR_OF_ROWS_IN_BLOCK on the next
        // input stream.
        //
        for ( int r = 0; r < inputRowSets.size() && row == null; r++ ) {
          nextInputStream();
          inputRowSet = currentInputStream();
          row = inputRowSet.getRowImmediate();
        }
        if ( row != null ) {
          incrementLinesRead();
        }
      } else {
        // What's the current input stream?
        inputRowSet = currentInputStream();
      }

      // To reduce stress on the locking system we are going to allow
      // The buffer to grow beyond "a few" entries.
      // We'll only do that if the previous step has not ended...
      //
      if ( isUsingThreadPriorityManagment()
        && !inputRowSet.isDone() && inputRowSet.size() <= lowerBufferBoundary && !isStopped() ) {
        try {
          Thread.sleep( 0, 1 );
        } catch ( InterruptedException e ) {
          // Ignore sleep interruption exception
        }
      }

      // See if this step is receiving partitioned data...
      // In that case it might be the case that one input row set is receiving
      // all data and
      // the other rowsets nothing. (repartitioning on the same key would do
      // that)
      //
      // We never guaranteed that the input rows would be read one by one
      // alternatively.
      // So in THIS particular case it is safe to just read 100 rows from one
      // rowset, then switch to another etc.
      // We can use timeouts to switch from one to another...
      //
      while ( row == null && !isStopped() ) {
        // Get a row from the input in row set ...
        // Timeout immediately if nothing is there to read.
        // We will then switch to the next row set to read from...
        //
        row = inputRowSet.getRowWait( 1, TimeUnit.MILLISECONDS );
        if ( row != null ) {
          incrementLinesRead();
          blockPointer++;
        } else {
          // Try once more...
          // If row is still empty and the row set is done, we remove the row
          // set from
          // the input stream and move on to the next one...
          //
          if ( inputRowSet.isDone() ) {
            row = inputRowSet.getRowWait( 1, TimeUnit.MILLISECONDS );
            if ( row == null ) {

              // Must release the read lock before acquisition of the write lock to prevent deadlocks.
              inputRowSetsLock.readLock().unlock();

              // Another thread might acquire the write lock before we do,
              // and invalidate the data we have just read.
              //
              // This is actually fine, until we only want to remove the current rowSet - ArrayList ignores non-existing
              // elements when removing.
              inputRowSetsLock.writeLock().lock();
              try {
                inputRowSets.remove( inputRowSet );
                if ( inputRowSets.isEmpty() ) {
                  return null; // We're completely done.
                }
              } finally {
                inputRowSetsLock.readLock().lock(); // downgrade to read lock
                inputRowSetsLock.writeLock().unlock();
              }
            } else {
              incrementLinesRead();
            }
          }
          nextInputStream();
          inputRowSet = currentInputStream();
        }
      }

      // This rowSet is perhaps no longer giving back rows?
      //
      while ( row == null && !stopped.get() ) {
        // Try the next input row set(s) until we find a row set that still has
        // rows...
        // The getRowFrom() method removes row sets from the input row sets
        // list.
        //
        if ( inputRowSets.isEmpty() ) {
          return null; // We're done.
        }

        nextInputStream();
        inputRowSet = currentInputStream();
        row = getRowFrom( inputRowSet );
      }
    } finally {
      inputRowSetsLock.readLock().unlock();
    }

    // Also set the meta data on the first occurrence.
    // or if prevSteps.length > 1 inputRowMeta can be changed
    if ( inputRowMeta == null || prevSteps.length > 1 ) {
      inputRowMeta = inputRowSet.getRowMeta();
    }

    if ( row != null ) {
      // OK, before we return the row, let's see if we need to check on mixing
      // row compositions...
      //
      if ( pipeline.isSafeModeEnabled() ) {
        pipelineMeta.checkRowMixingStatically( stepMeta, null );
      }

      for ( RowListener listener : rowListeners ) {
        listener.rowReadEvent( inputRowMeta, row );
      }
    }

    // Check the rejection rates etc. as well.
    verifyRejectionRates();

    return row;
  }

  /**
   * RowHandler controls how getRow/putRow are handled.
   * The default RowHandler will simply call
   * {@link #handleGetRow()} and {@link #handlePutRow(RowMetaInterface, Object[])}
   */
  public void setRowHandler( RowHandler rowHandler ) {
    Preconditions.checkNotNull( rowHandler );
    this.rowHandler = rowHandler;
  }

  public RowHandler getRowHandler() {
    if ( rowHandler == null ) {
      rowHandler = new DefaultRowHandler();
    }
    return this.rowHandler;
  }

  /**
   * Safe mode checking.
   *
   * @param row the row
   * @throws HopRowException the kettle row exception
   */
  protected void safeModeChecking( RowMetaInterface row ) throws HopRowException {
    if ( row == null ) {
      return;
    }

    if ( inputReferenceRow == null ) {
      inputReferenceRow = row.clone(); // copy it!

      // Check for double field names.
      //
      String[] fieldnames = row.getFieldNames();
      Arrays.sort( fieldnames );
      for ( int i = 0; i < fieldnames.length - 1; i++ ) {
        if ( fieldnames[ i ].equals( fieldnames[ i + 1 ] ) ) {
          throw new HopRowException( BaseMessages.getString(
            PKG, "BaseStep.SafeMode.Exception.DoubleFieldnames", fieldnames[ i ] ) );
        }
      }
    } else {
      safeModeChecking( inputReferenceRow, row );
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#identifyErrorOutput()
   */
  @Override
  public void identifyErrorOutput() {
    if ( stepMeta.isDoingErrorHandling() ) {
      StepErrorMeta stepErrorMeta = stepMeta.getStepErrorMeta();
      outputRowSetsLock.writeLock().lock();
      try {
        for ( int rowsetNr = 0; rowsetNr < outputRowSets.size(); rowsetNr++ ) {
          RowSet outputRowSet = outputRowSets.get( rowsetNr );
          if ( outputRowSet.getDestinationStepName().equalsIgnoreCase( stepErrorMeta.getTargetStep().getName() ) ) {
            // This is the rowset to move!
            //
            errorRowSet = outputRowSet;
            outputRowSets.remove( rowsetNr );
            return;
          }
        }
      } finally {
        outputRowSetsLock.writeLock().unlock();
      }
    }
  }

  /**
   * Safe mode checking.
   *
   * @param referenceRowMeta the reference row meta
   * @param rowMeta          the row meta
   * @throws HopRowException the kettle row exception
   */
  public static void safeModeChecking( RowMetaInterface referenceRowMeta, RowMetaInterface rowMeta )
    throws HopRowException {
    // See if the row we got has the same layout as the reference row.
    // First check the number of fields
    //
    if ( referenceRowMeta.size() != rowMeta.size() ) {
      throw new HopRowException( BaseMessages.getString( PKG, "BaseStep.SafeMode.Exception.VaryingSize", ""
        + referenceRowMeta.size(), "" + rowMeta.size(), rowMeta.toString() ) );
    } else {
      // Check field by field for the position of the names...
      for ( int i = 0; i < referenceRowMeta.size(); i++ ) {
        ValueMetaInterface referenceValue = referenceRowMeta.getValueMeta( i );
        ValueMetaInterface compareValue = rowMeta.getValueMeta( i );

        if ( !referenceValue.getName().equalsIgnoreCase( compareValue.getName() ) ) {
          throw new HopRowException( BaseMessages.getString(
            PKG, "BaseStep.SafeMode.Exception.MixingLayout", "" + ( i + 1 ), referenceValue.getName()
              + " " + referenceValue.toStringMeta(), compareValue.getName()
              + " " + compareValue.toStringMeta() ) );
        }

        if ( referenceValue.getType() != compareValue.getType() ) {
          throw new HopRowException( BaseMessages.getString( PKG, "BaseStep.SafeMode.Exception.MixingTypes", ""
            + ( i + 1 ), referenceValue.getName() + " " + referenceValue.toStringMeta(), compareValue.getName()
            + " " + compareValue.toStringMeta() ) );
        }

        if ( referenceValue.getStorageType() != compareValue.getStorageType() ) {
          throw new HopRowException( BaseMessages.getString(
            PKG, "BaseStep.SafeMode.Exception.MixingStorageTypes", "" + ( i + 1 ), referenceValue.getName()
              + " " + referenceValue.toStringMeta(), compareValue.getName()
              + " " + compareValue.toStringMeta() ) );
        }
      }
    }
  }

  /**
   * Gets the row from.
   *
   * @param rowSet the row set
   * @return the row from
   * @throws HopStepException the kettle step exception
   */
  public Object[] getRowFrom( RowSet rowSet ) throws HopStepException {
    return getRowHandler().getRowFrom( rowSet );
  }

  public Object[] handleGetRowFrom( RowSet rowSet ) throws HopStepException {
    // Are we pausing the step? If so, stall forever...
    //
    while ( paused.get() && !stopped.get() ) {
      try {
        Thread.sleep( 10 );
      } catch ( InterruptedException e ) {
        throw new HopStepException( e );
      }
    }

    // Have all threads started?
    // Are we running yet? If not, wait a bit until all threads have been
    // started.
    if ( this.checkPipelineRunning == false ) {
      while ( !pipeline.isRunning() && !stopped.get() ) {
        try {
          Thread.sleep( 1 );
        } catch ( InterruptedException e ) {
          // Ignore sleep interruption exception
        }
      }
      this.checkPipelineRunning = true;
    }
    Object[] rowData = null;

    // To reduce stress on the locking system we are going to allow
    // The buffer to grow beyond "a few" entries.
    // We'll only do that if the previous step has not ended...
    //
    if ( isUsingThreadPriorityManagment()
      && !rowSet.isDone() && rowSet.size() <= lowerBufferBoundary && !isStopped() ) {
      try {
        Thread.sleep( 0, 1 );
      } catch ( InterruptedException e ) {
        // Ignore sleep interruption exception
      }
    }

    // Grab a row... If nothing received after a timeout, try again.
    //
    rowData = rowSet.getRow();
    while ( rowData == null && !rowSet.isDone() && !stopped.get() ) {
      rowData = rowSet.getRow();

      // Verify deadlocks!
      //
      /*
       * if (rowData==null) { if (getInputRowSets().size()>1 && getLinesRead()==deadLockCounter) {
       * verifyInputDeadLock(); } deadLockCounter=getLinesRead(); }
       */
    }

    // Still nothing: no more rows to be had?
    //
    if ( rowData == null && rowSet.isDone() ) {
      // Try one more time to get a row to make sure we don't get a
      // race-condition between the get and the isDone()
      //
      rowData = rowSet.getRow();
    }

    if ( stopped.get() ) {
      if ( log.isDebug() ) {
        logDebug( BaseMessages.getString( PKG, "BaseStep.Log.StopLookingForMoreRows" ) );
      }
      stopAll();
      return null;
    }

    if ( rowData == null && rowSet.isDone() ) {
      // Try one more time...
      //
      rowData = rowSet.getRow();
      if ( rowData == null ) {

        // Must release the read lock before acquisition of the write lock to prevent deadlocks.
        //
        // But #handleGetRowFrom() can be called either from outside or from handleGetRow().
        // So a current thread might hold the read lock (possibly reentrantly) and might not.
        // We therefore must release it conditionally.
        int holdCount = inputRowSetsLock.getReadHoldCount();
        for ( int i = 0; i < holdCount; i++ ) {
          inputRowSetsLock.readLock().unlock();
        }
        // Just like in handleGetRow() method, an another thread might acquire the write lock before we do.
        // Here this is also fine, until we only want to remove the given rowSet - ArrayList ignores non-existing
        // elements when removing.
        inputRowSetsLock.writeLock().lock();
        try {
          inputRowSets.remove( rowSet );

          // Downgrade to read lock by restoring to the previous state before releasing the write lock
          for ( int i = 0; i < holdCount; i++ ) {
            inputRowSetsLock.readLock().lock();
          }

          return null;
        } finally {
          inputRowSetsLock.writeLock().unlock();
        }
      }
    }
    incrementLinesRead();

    // call all rowlisteners...
    //
    for ( RowListener listener : rowListeners ) {
      listener.rowReadEvent( rowSet.getRowMeta(), rowData );
    }

    return rowData;
  }

  /**
   * - A step sees that it can't get a new row from input in the step. - Then it verifies that there is more than one
   * input row set and that at least one is full and at least one is empty. - Then it finds a step in the pipeline
   * (situated before the reader step) which has at least one full and one empty output row set. - If this situation
   * presents itself and if it happens twice with the same rows read count (meaning: stalled reading step) we throw an
   * exception. For the attached example that exception is:
   *
   * @throws HopStepException
   */
  protected void verifyInputDeadLock() throws HopStepException {
    RowSet inputFull = null;
    RowSet inputEmpty = null;
    for ( RowSet rowSet : getInputRowSets() ) {
      if ( rowSet.size() == pipeline.getRowSetSize() ) {
        inputFull = rowSet;
      } else if ( rowSet.size() == 0 ) {
        inputEmpty = rowSet;
      }
    }
    if ( inputFull != null && inputEmpty != null ) {
      // Find a step where
      // - the input rowset are full
      // - one output rowset is full
      // - one output is empty
      for ( StepMetaDataCombi combi : pipeline.getSteps() ) {
        int inputSize = 0;
        List<RowSet> combiInputRowSets = combi.step.getInputRowSets();
        int totalSize = combiInputRowSets.size() * pipeline.getRowSetSize();
        for ( RowSet rowSet : combiInputRowSets ) {
          inputSize += rowSet.size();
        }
        // All full probably means a stalled step.
        List<RowSet> combiOutputRowSets = combi.step.getOutputRowSets();
        if ( inputSize > 0 && inputSize == totalSize && combiOutputRowSets.size() > 1 ) {
          RowSet outputFull = null;
          RowSet outputEmpty = null;
          for ( RowSet rowSet : combiOutputRowSets ) {
            if ( rowSet.size() == pipeline.getRowSetSize() ) {
              outputFull = rowSet;
            } else if ( rowSet.size() == 0 ) {
              outputEmpty = rowSet;
            }
          }
          if ( outputFull != null && outputEmpty != null ) {
            // Verify that this step is lated before the current one
            //
            if ( pipelineMeta.findPrevious( stepMeta, combi.stepMeta ) ) {
              throw new HopStepException( "A deadlock was detected between steps '"
                + combi.stepname + "' and '" + stepname
                + "'.  The steps are both waiting for each other because a series of row set buffers filled up." );
            }
          }
        }
      }
    }
  }

  /**
   * Find input row set.
   *
   * @param sourceStep the source step
   * @return the row set
   * @throws HopStepException the kettle step exception
   */
  public RowSet findInputRowSet( String sourceStep ) throws HopStepException {
    // Check to see that "sourceStep" only runs in a single copy
    // Otherwise you'll see problems during execution.
    //
    StepMeta sourceStepMeta = pipelineMeta.findStep( sourceStep );
    if ( sourceStepMeta == null ) {
      throw new HopStepException( BaseMessages.getString(
        PKG, "BaseStep.Exception.SourceStepToReadFromDoesntExist", sourceStep ) );
    }

    if ( sourceStepMeta.getCopies() > 1 ) {
      throw new HopStepException( BaseMessages.getString(
        PKG, "BaseStep.Exception.SourceStepToReadFromCantRunInMultipleCopies", sourceStep, Integer
          .toString( sourceStepMeta.getCopies() ) ) );
    }

    return findInputRowSet( sourceStep, 0, getStepname(), getCopy() );
  }

  /**
   * Find input row set.
   *
   * @param from     the from
   * @param fromcopy the fromcopy
   * @param to       the to
   * @param tocopy   the tocopy
   * @return the row set
   */
  public RowSet findInputRowSet( String from, int fromcopy, String to, int tocopy ) {
    inputRowSetsLock.readLock().lock();
    try {
      for ( RowSet rs : inputRowSets ) {
        if ( rs.getOriginStepName().equalsIgnoreCase( from )
          && rs.getDestinationStepName().equalsIgnoreCase( to ) && rs.getOriginStepCopy() == fromcopy
          && rs.getDestinationStepCopy() == tocopy ) {
          return rs;
        }
      }
    } finally {
      inputRowSetsLock.readLock().unlock();
    }

    // See if the rowset is part of the output of a mapping source step...
    //
    // Lookup step "From"
    //
    StepMeta mappingStep = pipelineMeta.findStep( from );

    // See if it's a mapping
    //
    if ( mappingStep != null && mappingStep.isMapping() ) {

      // In this case we can cast the step thread to a Mapping...
      //
      List<StepInterface> baseSteps = pipeline.findBaseSteps( from );
      if ( baseSteps.size() == 1 ) {
        Mapping mapping = (Mapping) baseSteps.get( 0 );

        // Find the appropriate rowset in the mapping...
        // The rowset in question has been passed over to a Mapping Input step inside the Mapping pipeline.
        //
        MappingOutput[] outputs = mapping.getMappingPipeline().findMappingOutput();
        for ( MappingOutput output : outputs ) {
          for ( RowSet rs : output.getOutputRowSets() ) {
            // The destination is what counts here...
            //
            if ( rs.getDestinationStepName().equalsIgnoreCase( to ) ) {
              return rs;
            }
          }
        }
      }
    }

    return null;
  }

  /**
   * Find output row set.
   *
   * @param targetStep the target step
   * @return the row set
   * @throws HopStepException the kettle step exception
   */
  public RowSet findOutputRowSet( String targetStep ) throws HopStepException {

    // Check to see that "targetStep" only runs in a single copy
    // Otherwise you'll see problems during execution.
    //
    StepMeta targetStepMeta = pipelineMeta.findStep( targetStep );
    if ( targetStepMeta == null ) {
      throw new HopStepException( BaseMessages.getString(
        PKG, "BaseStep.Exception.TargetStepToWriteToDoesntExist", targetStep ) );
    }

    if ( targetStepMeta.getCopies() > 1 ) {
      throw new HopStepException( BaseMessages.getString(
        PKG, "BaseStep.Exception.TargetStepToWriteToCantRunInMultipleCopies", targetStep, Integer
          .toString( targetStepMeta.getCopies() ) ) );
    }

    return findOutputRowSet( getStepname(), getCopy(), targetStep, 0 );
  }

  /**
   * Find an output rowset in a running pipeline. It will also look at the "to" step to see if this is a mapping.
   * If it is, it will find the appropriate rowset in that pipeline.
   *
   * @param from
   * @param fromcopy
   * @param to
   * @param tocopy
   * @return The rowset or null if none is found.
   */
  public RowSet findOutputRowSet( String from, int fromcopy, String to, int tocopy ) {
    outputRowSetsLock.readLock().lock();
    try {
      for ( RowSet rs : outputRowSets ) {
        if ( rs.getOriginStepName().equalsIgnoreCase( from )
          && rs.getDestinationStepName().equalsIgnoreCase( to ) && rs.getOriginStepCopy() == fromcopy
          && rs.getDestinationStepCopy() == tocopy ) {
          return rs;
        }
      }
    } finally {
      outputRowSetsLock.readLock().unlock();
    }

    // See if the rowset is part of the input of a mapping target step...
    //
    // Lookup step "To"
    //
    StepMeta mappingStep = pipelineMeta.findStep( to );

    // See if it's a mapping
    //
    if ( mappingStep != null && mappingStep.isMapping() ) {

      // In this case we can cast the step thread to a Mapping...
      //
      List<StepInterface> baseSteps = pipeline.findBaseSteps( to );
      if ( baseSteps.size() == 1 ) {
        Mapping mapping = (Mapping) baseSteps.get( 0 );

        // Find the appropriate rowset in the mapping...
        // The rowset in question has been passed over to a Mapping Input step inside the Mapping pipeline.
        //
        MappingInput[] inputs = mapping.getMappingPipeline().findMappingInput();
        for ( MappingInput input : inputs ) {
          for ( RowSet rs : input.getInputRowSets() ) {
            // The source step is what counts in this case...
            //
            if ( rs.getOriginStepName().equalsIgnoreCase( from ) ) {
              return rs;
            }
          }
        }
      }
    }

    // Still nothing found!
    //
    return null;
  }

  //
  // We have to tell the next step we're finished with
  // writing to output rowset(s)!
  //
  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#setOutputDone()
   */
  @Override
  public void setOutputDone() {
    outputRowSetsLock.readLock().lock();
    try {
      if ( log.isDebug() ) {
        logDebug( BaseMessages.getString( PKG, "BaseStep.Log.OutputDone", String.valueOf( outputRowSets.size() ) ) );
      }
      for ( RowSet rs : outputRowSets ) {
        rs.setDone();
      }
      if ( errorRowSet != null ) {
        errorRowSet.setDone();
      }
    } finally {
      outputRowSetsLock.readLock().unlock();
    }
  }

  /**
   * This method finds the surrounding steps and rowsets for this base step. This steps keeps it's own list of rowsets
   * (etc.) to prevent it from having to search every time.
   * <p>
   * Note that all rowsets input and output is already created by pipeline itself. So
   * in this place we will look and choose which rowsets will be used by this particular step.
   * <p>
   * We will collect all input rowsets and output rowsets so step will be able to read input data,
   * and write to the output.
   * <p>
   * Steps can run in multiple copies, on in partitioned fashion. For this case we should take
   * in account that in different cases we should take in account one to one, one to many and other cases
   * properly.
   */
  public void dispatch() {
    if ( pipelineMeta == null ) { // for preview reasons, no dispatching is done!
      return;
    }

    StepMeta stepMeta = pipelineMeta.findStep( stepname );

    if ( log.isDetailed() ) {
      logDetailed( BaseMessages.getString( PKG, "BaseStep.Log.StartingBuffersAllocation" ) );
    }

    // How many next steps are there? 0, 1 or more??
    // How many steps do we send output to?
    List<StepMeta> previousSteps = pipelineMeta.findPreviousSteps( stepMeta, true );
    List<StepMeta> succeedingSteps = pipelineMeta.findNextSteps( stepMeta );

    int nrInput = previousSteps.size();
    int nrOutput = succeedingSteps.size();

    inputRowSetsLock.writeLock().lock();
    outputRowSetsLock.writeLock().lock();
    try {
      inputRowSets = new ArrayList<>();
      outputRowSets = new ArrayList<>();

      errorRowSet = null;
      prevSteps = new StepMeta[ nrInput ];
      nextSteps = new StepMeta[ nrOutput ];

      currentInputRowSetNr = 0; // we start with input[0];

      if ( log.isDetailed() ) {
        logDetailed( BaseMessages.getString( PKG, "BaseStep.Log.StepInfo", String.valueOf( nrInput ), String
          .valueOf( nrOutput ) ) );
      }
      // populate input rowsets.
      for ( int i = 0; i < previousSteps.size(); i++ ) {
        prevSteps[ i ] = previousSteps.get( i );
        if ( log.isDetailed() ) {
          logDetailed( BaseMessages.getString(
            PKG, "BaseStep.Log.GotPreviousStep", stepname, String.valueOf( i ), prevSteps[ i ].getName() ) );
        }

        // Looking at the previous step, you can have either 1 rowset to look at or more then one.
        int prevCopies = prevSteps[ i ].getCopies();
        int nextCopies = stepMeta.getCopies();
        if ( log.isDetailed() ) {
          logDetailed( BaseMessages.getString(
            PKG, "BaseStep.Log.InputRowInfo", String.valueOf( prevCopies ), String.valueOf( nextCopies ) ) );
        }

        int nrCopies;
        int dispatchType;
        boolean repartitioning;
        if ( prevSteps[ i ].isPartitioned() ) {
          repartitioning = !prevSteps[ i ].getStepPartitioningMeta()
            .equals( stepMeta.getStepPartitioningMeta() );
        } else {
          repartitioning = stepMeta.isPartitioned();
        }

        if ( prevCopies == 1 && nextCopies == 1 ) {
          // normal hop
          dispatchType = Pipeline.TYPE_DISP_1_1;
          nrCopies = 1;
        } else if ( prevCopies == 1 && nextCopies > 1 ) {
          // one to many hop
          dispatchType = Pipeline.TYPE_DISP_1_N;
          nrCopies = 1;
        } else if ( prevCopies > 1 && nextCopies == 1 ) {
          // from many to one hop
          dispatchType = Pipeline.TYPE_DISP_N_1;
          nrCopies = prevCopies;
        } else if ( prevCopies == nextCopies && !repartitioning ) {
          // this may be many-to-many or swim-lanes hop
          dispatchType = Pipeline.TYPE_DISP_N_N;
          nrCopies = 1;
        } else { // > 1!
          dispatchType = Pipeline.TYPE_DISP_N_M;
          nrCopies = prevCopies;
        }

        for ( int c = 0; c < nrCopies; c++ ) {
          RowSet rowSet = null;
          switch ( dispatchType ) {
            case Pipeline.TYPE_DISP_1_1:
              rowSet = pipeline.findRowSet( prevSteps[ i ].getName(), 0, stepname, 0 );
              break;
            case Pipeline.TYPE_DISP_1_N:
              rowSet = pipeline.findRowSet( prevSteps[ i ].getName(), 0, stepname, getCopy() );
              break;
            case Pipeline.TYPE_DISP_N_1:
              rowSet = pipeline.findRowSet( prevSteps[ i ].getName(), c, stepname, 0 );
              break;
            case Pipeline.TYPE_DISP_N_N:
              rowSet = pipeline.findRowSet( prevSteps[ i ].getName(), getCopy(), stepname, getCopy() );
              break;
            case Pipeline.TYPE_DISP_N_M:
              rowSet = pipeline.findRowSet( prevSteps[ i ].getName(), c, stepname, getCopy() );
              break;
            default:
              break;
          }
          if ( rowSet != null ) {
            inputRowSets.add( rowSet );
            if ( log.isDetailed() ) {
              logDetailed( BaseMessages.getString( PKG, "BaseStep.Log.FoundInputRowset", rowSet.getName() ) );
            }
          } else {
            if ( !prevSteps[ i ].isMapping() && !stepMeta.isMapping() ) {
              logError( BaseMessages.getString( PKG, "BaseStep.Log.UnableToFindInputRowset" ) );
              setErrors( 1 );
              stopAll();
              return;
            }
          }
        }
      }
      // And now the output part!
      for ( int i = 0; i < nrOutput; i++ ) {
        nextSteps[ i ] = succeedingSteps.get( i );

        int prevCopies = stepMeta.getCopies();
        int nextCopies = nextSteps[ i ].getCopies();

        if ( log.isDetailed() ) {
          logDetailed( BaseMessages.getString(
            PKG, "BaseStep.Log.OutputRowInfo", String.valueOf( prevCopies ), String.valueOf( nextCopies ) ) );
        }

        int nrCopies;
        int dispatchType;
        boolean repartitioning;
        if ( stepMeta.isPartitioned() ) {
          repartitioning = !stepMeta.getStepPartitioningMeta()
            .equals( nextSteps[ i ].getStepPartitioningMeta() );
        } else {
          repartitioning = nextSteps[ i ].isPartitioned();
        }

        if ( prevCopies == 1 && nextCopies == 1 ) {
          dispatchType = Pipeline.TYPE_DISP_1_1;
          nrCopies = 1;
        } else if ( prevCopies == 1 && nextCopies > 1 ) {
          dispatchType = Pipeline.TYPE_DISP_1_N;
          nrCopies = nextCopies;
        } else if ( prevCopies > 1 && nextCopies == 1 ) {
          dispatchType = Pipeline.TYPE_DISP_N_1;
          nrCopies = 1;
        } else if ( prevCopies == nextCopies && !repartitioning ) {
          dispatchType = Pipeline.TYPE_DISP_N_N;
          nrCopies = 1;
        } else { // > 1!
          dispatchType = Pipeline.TYPE_DISP_N_M;
          nrCopies = nextCopies;
        }

        for ( int c = 0; c < nrCopies; c++ ) {
          RowSet rowSet = null;
          switch ( dispatchType ) {
            case Pipeline.TYPE_DISP_1_1:
              rowSet = pipeline.findRowSet( stepname, 0, nextSteps[ i ].getName(), 0 );
              break;
            case Pipeline.TYPE_DISP_1_N:
              rowSet = pipeline.findRowSet( stepname, 0, nextSteps[ i ].getName(), c );
              break;
            case Pipeline.TYPE_DISP_N_1:
              rowSet = pipeline.findRowSet( stepname, getCopy(), nextSteps[ i ].getName(), 0 );
              break;
            case Pipeline.TYPE_DISP_N_N:
              rowSet = pipeline.findRowSet( stepname, getCopy(), nextSteps[ i ].getName(), getCopy() );
              break;
            case Pipeline.TYPE_DISP_N_M:
              rowSet = pipeline.findRowSet( stepname, getCopy(), nextSteps[ i ].getName(), c );
              break;
            default:
              break;
          }
          if ( rowSet != null ) {
            outputRowSets.add( rowSet );
            if ( log.isDetailed() ) {
              logDetailed( BaseMessages.getString( PKG, "BaseStep.Log.FoundOutputRowset", rowSet.getName() ) );
            }
          } else {
            if ( !stepMeta.isMapping() && !nextSteps[ i ].isMapping() ) {
              logError( BaseMessages.getString( PKG, "BaseStep.Log.UnableToFindOutputRowset" ) );
              setErrors( 1 );
              stopAll();
              return;
            }
          }
        }
      }
    } finally {
      inputRowSetsLock.writeLock().unlock();
      outputRowSetsLock.writeLock().unlock();
    }

    if ( stepMeta.getTargetStepPartitioningMeta() != null ) {
      nextStepPartitioningMeta = stepMeta.getTargetStepPartitioningMeta();
    }

    if ( log.isDetailed() ) {
      logDetailed( BaseMessages.getString( PKG, "BaseStep.Log.FinishedDispatching" ) );
    }
  }

  /**
   * Checks if is basic.
   *
   * @return true, if is basic
   */
  public boolean isBasic() {
    return log.isBasic();
  }

  /**
   * Checks if is detailed.
   *
   * @return true, if is detailed
   */
  public boolean isDetailed() {
    return log.isDetailed();
  }

  /**
   * Checks if is debug.
   *
   * @return true, if is debug
   */
  public boolean isDebug() {
    return log.isDebug();
  }

  /**
   * Checks if is row level.
   *
   * @return true, if is row level
   */
  public boolean isRowLevel() {
    return log.isRowLevel();
  }

  /**
   * Log minimal.
   *
   * @param message the message
   */
  public void logMinimal( String message ) {
    log.logMinimal( message );
  }

  /**
   * Log minimal.
   *
   * @param message   the message
   * @param arguments the arguments
   */
  public void logMinimal( String message, Object... arguments ) {
    log.logMinimal( message, arguments );
  }

  /**
   * Log basic.
   *
   * @param message the message
   */
  public void logBasic( String message ) {
    log.logBasic( message );
  }

  /**
   * Log basic.
   *
   * @param message   the message
   * @param arguments the arguments
   */
  public void logBasic( String message, Object... arguments ) {
    log.logBasic( message, arguments );
  }

  /**
   * Log detailed.
   *
   * @param message the message
   */
  public void logDetailed( String message ) {
    log.logDetailed( message );
  }

  /**
   * Log detailed.
   *
   * @param message   the message
   * @param arguments the arguments
   */
  public void logDetailed( String message, Object... arguments ) {
    log.logDetailed( message, arguments );
  }

  /**
   * Log debug.
   *
   * @param message the message
   */
  public void logDebug( String message ) {
    log.logDebug( message );
  }

  /**
   * Log debug.
   *
   * @param message   the message
   * @param arguments the arguments
   */
  public void logDebug( String message, Object... arguments ) {
    log.logDebug( message, arguments );
  }

  /**
   * Log rowlevel.
   *
   * @param message the message
   */
  public void logRowlevel( String message ) {
    log.logRowlevel( message );
  }

  /**
   * Log rowlevel.
   *
   * @param message   the message
   * @param arguments the arguments
   */
  public void logRowlevel( String message, Object... arguments ) {
    log.logRowlevel( message, arguments );
  }

  /**
   * Log error.
   *
   * @param message the message
   */
  public void logError( String message ) {
    log.logError( message );
  }

  /**
   * Log error.
   *
   * @param message the message
   * @param e       the e
   */
  public void logError( String message, Throwable e ) {
    log.logError( message, e );
  }

  /**
   * Log error.
   *
   * @param message   the message
   * @param arguments the arguments
   */
  public void logError( String message, Object... arguments ) {
    log.logError( message, arguments );
  }

  /**
   * Gets the next class nr.
   *
   * @return the next class nr
   */
  public int getNextClassNr() {
    int ret = pipeline.class_nr;
    pipeline.class_nr++;

    return ret;
  }

  /**
   * Output is done.
   *
   * @return true, if successful
   */
  public boolean outputIsDone() {
    int nrstopped = 0;

    outputRowSetsLock.readLock().lock();
    try {
      for ( RowSet rs : outputRowSets ) {
        if ( rs.isDone() ) {
          nrstopped++;
        }
      }
      return nrstopped >= outputRowSets.size();
    } finally {
      outputRowSetsLock.readLock().unlock();
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#stopAll()
   */
  @Override
  public void stopAll() {
    stopped.set( true );
    pipeline.stopAll();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#isStopped()
   */
  @Override
  public boolean isStopped() {
    return stopped.get();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#isRunning()
   */
  @Override
  public boolean isRunning() {
    return running.get();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#isPaused()
   */
  @Override
  public boolean isPaused() {
    return paused.get();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#setStopped(boolean)
   */
  @Override
  public void setStopped( boolean stopped ) {
    this.stopped.set( stopped );
  }

  @Override
  public void setSafeStopped( boolean stopped ) {
    this.safeStopped.set( stopped );
  }

  @Override
  public boolean isSafeStopped() {
    return safeStopped.get();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#setRunning(boolean)
   */
  @Override
  public void setRunning( boolean running ) {
    this.running.set( running );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#pauseRunning()
   */
  @Override
  public void pauseRunning() {
    setPaused( true );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#resumeRunning()
   */
  @Override
  public void resumeRunning() {
    setPaused( false );
  }

  /**
   * Sets the paused.
   *
   * @param paused the new paused
   */
  public void setPaused( boolean paused ) {
    this.paused.set( paused );
  }

  /**
   * Sets the paused.
   *
   * @param paused the new paused
   */
  public void setPaused( AtomicBoolean paused ) {
    this.paused = paused;
  }

  /**
   * Checks if is initialising.
   *
   * @return true, if is initialising
   */
  public boolean isInitialising() {
    return init;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#markStart()
   */
  @Override
  public void markStart() {
    Calendar cal = Calendar.getInstance();
    start_time = cal.getTime();

    setInternalVariables();
  }

  /**
   * Sets the internal variables.
   */
  public void setInternalVariables() {
    setVariable( Const.INTERNAL_VARIABLE_STEP_NAME, stepname );
    setVariable( Const.INTERNAL_VARIABLE_STEP_COPYNR, Integer.toString( getCopy() ) );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#markStop()
   */
  @Override
  public void markStop() {
    Calendar cal = Calendar.getInstance();
    stop_time = cal.getTime();

    // Here we are completely done with the pipeline.
    // Call all the attached listeners and notify the outside world that the step has finished.
    //
    synchronized ( stepListeners ) {
      for ( StepListener stepListener : stepListeners ) {
        stepListener.stepFinished( pipeline, stepMeta, this );
      }
    }

    // We're finally completely done with this step.
    //
    setRunning( false );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#getRuntime()
   */
  @Override
  public long getRuntime() {
    long lapsed;
    if ( start_time != null && stop_time == null ) {
      Calendar cal = Calendar.getInstance();
      long now = cal.getTimeInMillis();
      long st = start_time.getTime();
      lapsed = now - st;
    } else if ( start_time != null && stop_time != null ) {
      lapsed = stop_time.getTime() - start_time.getTime();
    } else {
      lapsed = 0;
    }

    return lapsed;
  }

  /**
   * Builds the log.
   *
   * @param sname         the sname
   * @param copynr        the copynr
   * @param lines_read    the lines_read
   * @param lines_written the lines_written
   * @param lines_updated the lines_updated
   * @param lines_skipped the lines_skipped
   * @param errors        the errors
   * @param start_date    the start_date
   * @param end_date      the end_date
   * @return the row meta and data
   */
  public RowMetaAndData buildLog( String sname, int copynr, long lines_read, long lines_written,
                                  long lines_updated, long lines_skipped, long errors, Date start_date,
                                  Date end_date ) {
    RowMetaInterface r = new RowMeta();
    Object[] data = new Object[ 9 ];
    int nr = 0;

    r.addValueMeta( new ValueMetaString(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.Stepname" ) ) );
    data[ nr ] = sname;
    nr++;

    r.addValueMeta( new ValueMetaNumber(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.Copy" ) ) );
    data[ nr ] = (double) copynr;
    nr++;

    r.addValueMeta( new ValueMetaNumber(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.LinesReaded" ) ) );
    data[ nr ] = (double) lines_read;
    nr++;

    r.addValueMeta( new ValueMetaNumber(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.LinesWritten" ) ) );
    data[ nr ] = (double) lines_written;
    nr++;

    r.addValueMeta( new ValueMetaNumber(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.LinesUpdated" ) ) );
    data[ nr ] = (double) lines_updated;
    nr++;

    r.addValueMeta( new ValueMetaNumber(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.LinesSkipped" ) ) );
    data[ nr ] = (double) lines_skipped;
    nr++;

    r.addValueMeta( new ValueMetaNumber(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.Errors" ) ) );
    data[ nr ] = (double) errors;
    nr++;

    r.addValueMeta( new ValueMetaDate( "start_date" ) );
    data[ nr ] = start_date;
    nr++;

    r.addValueMeta( new ValueMetaDate( "end_date" ) );
    data[ nr ] = end_date;
    nr++;

    return new RowMetaAndData( r, data );
  }

  /**
   * Gets the log fields.
   *
   * @param comm the comm
   * @return the log fields
   */
  public static final RowMetaInterface getLogFields( String comm ) {
    RowMetaInterface r = new RowMeta();
    ValueMetaInterface sname =
      new ValueMetaString(
        BaseMessages.getString( PKG, "BaseStep.ColumnName.Stepname" ) );
    sname.setLength( 256 );
    r.addValueMeta( sname );

    r.addValueMeta( new ValueMetaNumber(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.Copy" ) ) );
    r.addValueMeta( new ValueMetaNumber(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.LinesReaded" ) ) );
    r.addValueMeta( new ValueMetaNumber(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.LinesWritten" ) ) );
    r.addValueMeta( new ValueMetaNumber(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.LinesUpdated" ) ) );
    r.addValueMeta( new ValueMetaNumber(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.LinesSkipped" ) ) );
    r.addValueMeta( new ValueMetaNumber(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.Errors" ) ) );
    r.addValueMeta( new ValueMetaDate(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.StartDate" ) ) );
    r.addValueMeta( new ValueMetaDate(
      BaseMessages.getString( PKG, "BaseStep.ColumnName.EndDate" ) ) );

    for ( int i = 0; i < r.size(); i++ ) {
      r.getValueMeta( i ).setOrigin( comm );
    }

    return r;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.lang.Object#toString()
   */
  @Override
  public String toString() {
    StringBuilder string = new StringBuilder( 50 );

    // If the step runs in a mapping (and as such has a "parent pipeline", we are going to print the name of the
    // pipeline during logging
    //
    //
    if ( !Utils.isEmpty( getPipeline().getMappingStepName() ) ) {
      string.append( '[' ).append( pipeline.toString() ).append( ']' ).append( '.' ); // Name of the mapping pipeline
    }

    if ( !Utils.isEmpty( partitionID ) ) {
      string.append( stepname ).append( '.' ).append( partitionID );
    } else {
      string.append( stepname ).append( '.' ).append( getCopy() );
    }

    return string.toString();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#rowsetOutputSize()
   */
  @Override
  public int rowsetOutputSize() {
    int size = 0;

    outputRowSetsLock.readLock().lock();
    try {
      for ( RowSet outputRowSet : outputRowSets ) {
        size += outputRowSet.size();
      }
    } finally {
      outputRowSetsLock.readLock().unlock();
    }

    return size;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#rowsetInputSize()
   */
  @Override
  public int rowsetInputSize() {
    int size = 0;

    inputRowSetsLock.readLock().lock();
    try {
      for ( RowSet inputRowSet : inputRowSets ) {
        size += inputRowSet.size();
      }
    } finally {
      inputRowSetsLock.readLock().unlock();
    }

    return size;
  }

  /**
   * Perform actions to stop a running step. This can be stopping running SQL queries (cancel), etc. Default it doesn't
   * do anything.
   *
   * @param stepDataInterface The interface to the step data containing the connections, resultsets, open files, etc.
   * @throws HopException in case something goes wrong
   */
  @Override
  public void stopRunning( StepMetaInterface stepMetaInterface, StepDataInterface stepDataInterface )
    throws HopException {
  }

  /**
   * Stops running operations This method is deprecated, please use the method specifying the metadata and data
   * interfaces.
   *
   * @deprecated use {@link #stopRunning(StepMetaInterface, StepDataInterface)}
   */
  @Deprecated
  public void stopRunning() {
  }

  /**
   * Log summary.
   */
  public void logSummary() {
    synchronized ( statusCountersLock ) {
      long li = getLinesInput();
      long lo = getLinesOutput();
      long lr = getLinesRead();
      long lw = getLinesWritten();
      long lu = getLinesUpdated();
      long lj = getLinesRejected();
      if ( li > 0 || lo > 0 || lr > 0 || lw > 0 || lu > 0 || lj > 0 || errors > 0 ) {
        logBasic( BaseMessages.getString( PKG, "BaseStep.Log.SummaryInfo", String.valueOf( li ), String
          .valueOf( lo ), String.valueOf( lr ), String.valueOf( lw ), String.valueOf( lw ), String
          .valueOf( errors + lj ) ) );
      } else {
        logDetailed( BaseMessages.getString( PKG, "BaseStep.Log.SummaryInfo", String.valueOf( li ), String
          .valueOf( lo ), String.valueOf( lr ), String.valueOf( lw ), String.valueOf( lw ), String
          .valueOf( errors + lj ) ) );
      }
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#getStepID()
   */
  @Override
  public String getStepID() {
    if ( stepMeta != null ) {
      return stepMeta.getStepID();
    }
    return null;
  }

  /**
   * @return Returns the inputRowSets.
   */
  @Override
  public List<RowSet> getInputRowSets() {
    inputRowSetsLock.readLock().lock();
    try {
      return new ArrayList<>( inputRowSets );
    } finally {
      inputRowSetsLock.readLock().unlock();
    }
  }

  @Override
  public void addRowSetToInputRowSets( RowSet rowSet ) {
    inputRowSetsLock.writeLock().lock();
    try {
      inputRowSets.add( rowSet );
    } finally {
      inputRowSetsLock.writeLock().unlock();
    }
  }

  protected RowSet getFirstInputRowSet() {
    inputRowSetsLock.readLock().lock();
    try {
      return inputRowSets.get( 0 );
    } finally {
      inputRowSetsLock.readLock().unlock();
    }
  }

  protected void clearInputRowSets() {
    inputRowSetsLock.writeLock().lock();
    try {
      inputRowSets.clear();
    } finally {
      inputRowSetsLock.writeLock().unlock();
    }
  }

  protected void swapFirstInputRowSetIfExists( String stepName ) {
    inputRowSetsLock.writeLock().lock();
    try {
      for ( int i = 0; i < inputRowSets.size(); i++ ) {
        BlockingRowSet rs = (BlockingRowSet) inputRowSets.get( i );
        if ( rs.getOriginStepName().equalsIgnoreCase( stepName ) ) {
          // swap this one and position 0...that means, the main stream is always stream 0 --> easy!
          //
          BlockingRowSet zero = (BlockingRowSet) inputRowSets.get( 0 );
          inputRowSets.set( 0, rs );
          inputRowSets.set( i, zero );
        }
      }
    } finally {
      inputRowSetsLock.writeLock().unlock();
    }
  }

  /**
   * @param inputRowSets The inputRowSets to set.
   */
  public void setInputRowSets( List<RowSet> inputRowSets ) {
    inputRowSetsLock.writeLock().lock();
    try {
      this.inputRowSets = inputRowSets;
    } finally {
      inputRowSetsLock.writeLock().unlock();
    }
  }

  /**
   * @return Returns the outputRowSets.
   */
  @Override
  public List<RowSet> getOutputRowSets() {
    outputRowSetsLock.readLock().lock();
    try {
      return new ArrayList<>( outputRowSets );
    } finally {
      outputRowSetsLock.readLock().unlock();
    }
  }

  @Override
  public void addRowSetToOutputRowSets( RowSet rowSet ) {
    outputRowSetsLock.writeLock().lock();
    try {
      outputRowSets.add( rowSet );
    } finally {
      outputRowSetsLock.writeLock().unlock();
    }
  }

  protected void clearOutputRowSets() {
    outputRowSetsLock.writeLock().lock();
    try {
      outputRowSets.clear();
    } finally {
      outputRowSetsLock.writeLock().unlock();
    }
  }

  /**
   * @param outputRowSets The outputRowSets to set.
   */
  public void setOutputRowSets( List<RowSet> outputRowSets ) {
    outputRowSetsLock.writeLock().lock();
    try {
      this.outputRowSets = outputRowSets;
    } finally {
      outputRowSetsLock.writeLock().unlock();
    }
  }

  /**
   * @return Returns the distributed.
   */
  public boolean isDistributed() {
    return distributed;
  }

  /**
   * @param distributed The distributed to set.
   */
  public void setDistributed( boolean distributed ) {
    this.distributed = distributed;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#addRowListener(org.apache.hop.pipeline.step.RowListener)
   */
  @Override
  public void addRowListener( RowListener rowListener ) {
    rowListeners.add( rowListener );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#removeRowListener(org.apache.hop.pipeline.step.RowListener)
   */
  @Override
  public void removeRowListener( RowListener rowListener ) {
    rowListeners.remove( rowListener );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#getRowListeners()
   */
  @Override
  public List<RowListener> getRowListeners() {
    return Collections.unmodifiableList( rowListeners );
  }

  /**
   * Adds the result file.
   *
   * @param resultFile the result file
   */
  public void addResultFile( ResultFile resultFile ) {
    ReentrantReadWriteLock.WriteLock lock = resultFilesLock.writeLock();
    lock.lock();
    try {
      resultFiles.put( resultFile.getFile().toString(), resultFile );
    } finally {
      lock.unlock();
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#getResultFiles()
   */
  @Override
  public Map<String, ResultFile> getResultFiles() {
    ReentrantReadWriteLock.ReadLock lock = resultFilesLock.readLock();
    lock.lock();
    try {
      return new HashMap<String, ResultFile>( this.resultFiles );
    } finally {
      lock.unlock();
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#getStatus()
   */
  @Override
  public StepExecutionStatus getStatus() {
    // Is this thread alive or not?
    //
    if ( isRunning() ) {
      if ( isStopped() ) {
        return StepExecutionStatus.STATUS_HALTING;
      } else {
        if ( isPaused() ) {
          return StepExecutionStatus.STATUS_PAUSED;
        } else {
          return StepExecutionStatus.STATUS_RUNNING;
        }
      }
    } else {
      // Step is not running... What are we doing?
      //
      // An init thread is running...
      //
      if ( pipeline.isInitializing() ) {
        if ( isInitialising() ) {
          return StepExecutionStatus.STATUS_INIT;
        } else {
          // Done initializing, but other threads are still busy.
          // So this step is idle
          //
          return StepExecutionStatus.STATUS_IDLE;
        }
      } else {
        // It's not running, it's not initializing, so what is it doing?
        //
        if ( isStopped() ) {
          return StepExecutionStatus.STATUS_STOPPED;
        } else {
          // To be sure (race conditions and all), get the rest in StepDataInterface object:
          //
          StepDataInterface sdi = pipeline.getStepDataInterface( stepname, copyNr );
          if ( sdi != null ) {
            if ( sdi.getStatus() == StepExecutionStatus.STATUS_DISPOSED ) {
              return StepExecutionStatus.STATUS_FINISHED;
            } else {
              return sdi.getStatus();
            }
          }
          return StepExecutionStatus.STATUS_EMPTY;
        }
      }
    }
  }

  /**
   * @return the partitionID
   */
  @Override
  public String getPartitionID() {
    return partitionID;
  }

  /**
   * @param partitionID the partitionID to set
   */
  @Override
  public void setPartitionID( String partitionID ) {
    this.partitionID = partitionID;
  }

  /**
   * @return the partitionTargets
   */
  public Map<String, BlockingRowSet> getPartitionTargets() {
    return partitionTargets;
  }

  /**
   * @param partitionTargets the partitionTargets to set
   */
  public void setPartitionTargets( Map<String, BlockingRowSet> partitionTargets ) {
    this.partitionTargets = partitionTargets;
  }

  /**
   * @return the repartitioning type
   */
  public int getRepartitioning() {
    return repartitioning;
  }

  /**
   * @param repartitioning the repartitioning type to set
   */
  @Override
  public void setRepartitioning( int repartitioning ) {
    this.repartitioning = repartitioning;
  }

  /**
   * @return the partitioned
   */
  @Override
  public boolean isPartitioned() {
    return partitioned;
  }

  /**
   * @param partitioned the partitioned to set
   */
  @Override
  public void setPartitioned( boolean partitioned ) {
    this.partitioned = partitioned;
  }

  /**
   * Check feedback.
   *
   * @param lines the lines
   * @return true, if successful
   */
  protected boolean checkFeedback( long lines ) {
    return getPipeline().isFeedbackShown()
      && ( lines > 0 ) && ( getPipeline().getFeedbackSize() > 0 )
      && ( lines % getPipeline().getFeedbackSize() ) == 0;
  }

  /**
   * @return the rowMeta
   */
  public RowMetaInterface getInputRowMeta() {
    return inputRowMeta;
  }

  /**
   * @param rowMeta the rowMeta to set
   */
  public void setInputRowMeta( RowMetaInterface rowMeta ) {
    this.inputRowMeta = rowMeta;
  }

  /**
   * @return the errorRowMeta
   */
  public RowMetaInterface getErrorRowMeta() {
    return errorRowMeta;
  }

  /**
   * @param errorRowMeta the errorRowMeta to set
   */
  public void setErrorRowMeta( RowMetaInterface errorRowMeta ) {
    this.errorRowMeta = errorRowMeta;
  }

  /**
   * @return the previewRowMeta
   */
  public RowMetaInterface getPreviewRowMeta() {
    return previewRowMeta;
  }

  /**
   * @param previewRowMeta the previewRowMeta to set
   */
  public void setPreviewRowMeta( RowMetaInterface previewRowMeta ) {
    this.previewRowMeta = previewRowMeta;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.variables.VariableSpace#copyVariablesFrom(org.apache.hop.core.variables.VariableSpace)
   */
  @Override
  public void copyVariablesFrom( VariableSpace space ) {
    variables.copyVariablesFrom( space );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.variables.VariableSpace#environmentSubstitute(java.lang.String)
   */
  @Override
  public String environmentSubstitute( String aString ) {
    return variables.environmentSubstitute( aString );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.variables.VariableSpace#environmentSubstitute(java.lang.String[])
   */
  @Override
  public String[] environmentSubstitute( String[] aString ) {
    return variables.environmentSubstitute( aString );
  }

  @Override
  public String fieldSubstitute( String aString, RowMetaInterface rowMeta, Object[] rowData )
    throws HopValueException {
    return variables.fieldSubstitute( aString, rowMeta, rowData );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.variables.VariableSpace#getParentVariableSpace()
   */
  @Override
  public VariableSpace getParentVariableSpace() {
    return variables.getParentVariableSpace();
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.hop.core.variables.VariableSpace#setParentVariableSpace(org.apache.hop.core.variables.VariableSpace)
   */
  @Override
  public void setParentVariableSpace( VariableSpace parent ) {
    variables.setParentVariableSpace( parent );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.variables.VariableSpace#getVariable(java.lang.String, java.lang.String)
   */
  @Override
  public String getVariable( String variableName, String defaultValue ) {
    return variables.getVariable( variableName, defaultValue );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.variables.VariableSpace#getVariable(java.lang.String)
   */
  @Override
  public String getVariable( String variableName ) {
    return variables.getVariable( variableName );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.variables.VariableSpace#getBooleanValueOfVariable(java.lang.String, boolean)
   */
  @Override
  public boolean getBooleanValueOfVariable( String variableName, boolean defaultValue ) {
    if ( !Utils.isEmpty( variableName ) ) {
      String value = environmentSubstitute( variableName );
      if ( !Utils.isEmpty( value ) ) {
        return ValueMetaString.convertStringToBoolean( value );
      }
    }
    return defaultValue;
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.hop.core.variables.VariableSpace#initializeVariablesFrom(org.apache.hop.core.variables.VariableSpace)
   */
  @Override
  public void initializeVariablesFrom( VariableSpace parent ) {
    variables.initializeVariablesFrom( parent );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.variables.VariableSpace#listVariables()
   */
  @Override
  public String[] listVariables() {
    return variables.listVariables();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.variables.VariableSpace#setVariable(java.lang.String, java.lang.String)
   */
  @Override
  public void setVariable( String variableName, String variableValue ) {
    variables.setVariable( variableName, variableValue );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.variables.VariableSpace#shareVariablesWith(org.apache.hop.core.variables.VariableSpace)
   */
  @Override
  public void shareVariablesWith( VariableSpace space ) {
    variables = space;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.variables.VariableSpace#injectVariables(java.util.Map)
   */
  @Override
  public void injectVariables( Map<String, String> prop ) {
    variables.injectVariables( prop );
  }

  /**
   * Returns the step ID via the getStepID() method call. Support for CheckResultSourceInterface.
   *
   * @return getStepID()
   */
  public String getTypeId() {
    return this.getStepID();
  }

  /**
   * Set to true to actively manage priorities of step threads.
   *
   * @param usingThreadPriorityManagment set to true to actively manage priorities of step threads
   */
  @Override
  public void setUsingThreadPriorityManagment( boolean usingThreadPriorityManagment ) {
    this.usingThreadPriorityManagment = usingThreadPriorityManagment;
  }

  /**
   * Retusn true if we are actively managing priorities of step threads.
   *
   * @return true if we are actively managing priorities of step threads
   */
  @Override
  public boolean isUsingThreadPriorityManagment() {
    return usingThreadPriorityManagment;
  }

  /**
   * This method is executed by Pipeline right before the threads start and right after initialization.
   * <p>
   * More to the point: here we open remote output step sockets.
   *
   * @throws HopStepException In case there is an error
   */
  @Override
  public void initBeforeStart() throws HopStepException {
  }

  /**
   * Returns the step listeners.
   *
   * @return the stepListeners
   */
  public List<StepListener> getStepListeners() {
    return stepListeners;
  }

  /**
   * Sets the step listeners.
   *
   * @param stepListeners the stepListeners to set
   */
  public void setStepListeners( List<StepListener> stepListeners ) {
    this.stepListeners = Collections.synchronizedList( stepListeners );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#processRow(org.apache.hop.pipeline.step.StepMetaInterface,
   * org.apache.hop.pipeline.step.StepDataInterface)
   */
  @Override
  public boolean processRow( StepMetaInterface smi, StepDataInterface sdi ) throws HopException {
    return false;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#canProcessOneRow()
   */
  @Override
  public boolean canProcessOneRow() {
    inputRowSetsLock.readLock().lock();
    try {
      switch ( inputRowSets.size() ) {
        case 0:
          return false;
        case 1:
          RowSet set = inputRowSets.get( 0 );
          if ( set.isDone() ) {
            return false;
          }
          return set.size() > 0;
        default:
          boolean allDone = true;
          for ( RowSet rowSet : inputRowSets ) {
            if ( !rowSet.isDone() ) {
              allDone = false;
            }
            if ( rowSet.size() > 0 ) {
              return true;
            }
          }
          return !allDone;
      }
    } finally {
      inputRowSetsLock.readLock().unlock();
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#addStepListener(org.apache.hop.pipeline.step.StepListener)
   */
  @Override
  public void addStepListener( StepListener stepListener ) {
    stepListeners.add( stepListener );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#isMapping()
   */
  @Override
  public boolean isMapping() {
    return stepMeta.isMapping();
  }

  /**
   * Retutns the socket repository.
   *
   * @return the socketRepository
   */
  public SocketRepository getSocketRepository() {
    return socketRepository;
  }

  /**
   * Sets the socket repository.
   *
   * @param socketRepository the socketRepository to set
   */
  public void setSocketRepository( SocketRepository socketRepository ) {
    this.socketRepository = socketRepository;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.logging.LoggingObjectInterface#getObjectName()
   */
  @Override
  public String getObjectName() {
    return getStepname();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#getLogChannel()
   */
  @Override
  public LogChannelInterface getLogChannel() {
    return log;
  }

  @Override public String getName() {
    return stepname;
  }

  /**
   * Gets copyNr
   *
   * @return value of copyNr
   */
  public int getCopyNr() {
    return copyNr;
  }

  /**
   * @param copyNr The copyNr to set
   */
  public void setCopyNr( int copyNr ) {
    this.copyNr = copyNr;
  }

  @Override public String getLogText() {
    StringBuffer buffer = HopLogStore.getAppender().getBuffer( log.getLogChannelId(), false );
    if ( buffer == null ) {
      return null;
    }
    return buffer.toString();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.logging.LoggingObjectInterface#getFilename()
   */
  @Override
  public String getFilename() {
    return null;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.logging.LoggingObjectInterface#getLogChannelId()
   */
  @Override
  public String getLogChannelId() {
    return log.getLogChannelId();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.logging.LoggingObjectInterface#getObjectType()
   */
  @Override
  public LoggingObjectType getObjectType() {
    return LoggingObjectType.STEP;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.logging.LoggingObjectInterface#getParent()
   */
  @Override
  public LoggingObjectInterface getParent() {
    return pipeline;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.logging.LoggingObjectInterface#getObjectCopy()
   */
  @Override
  public String getObjectCopy() {
    return Integer.toString( copyNr );
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.core.logging.LoggingObjectInterface#getLogLevel()
   */
  @Override
  public LogLevel getLogLevel() {
    return log != null ? log.getLogLevel() : null;
  }

  /**
   * Sets the log level.
   *
   * @param logLevel the new log level
   */
  public void setLogLevel( LogLevel logLevel ) {
    log.setLogLevel( logLevel );
  }

  /**
   * Close quietly.
   *
   * @param cl the object that can be closed.
   */
  public static void closeQuietly( Closeable cl ) {
    if ( cl != null ) {
      try {
        cl.close();
      } catch ( IOException ignored ) {
        // Ignore IOException on close
      }
    }
  }

  /**
   * Returns the container object ID.
   *
   * @return the containerObjectId
   */
  @Override
  public String getContainerObjectId() {
    return containerObjectId;
  }

  /**
   * Sets the container object ID.
   *
   * @param containerObjectId the containerObjectId to set
   */
  public void setCarteObjectId( String containerObjectId ) {
    this.containerObjectId = containerObjectId;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hop.pipeline.step.StepInterface#batchComplete()
   */
  @Override
  public void batchComplete() throws HopException {
  }

  /**
   * Returns the registration date
   *
   * @rerturn the registration date
   */
  @Override
  public Date getRegistrationDate() {
    return null;
  }

  @Override
  public boolean isGatheringMetrics() {
    return log != null && log.isGatheringMetrics();
  }

  @Override
  public void setGatheringMetrics( boolean gatheringMetrics ) {
    if ( log != null ) {
      log.setGatheringMetrics( gatheringMetrics );
    }
  }

  @Override
  public boolean isForcingSeparateLogging() {
    return log != null && log.isForcingSeparateLogging();
  }

  @Override
  public void setForcingSeparateLogging( boolean forcingSeparateLogging ) {
    if ( log != null ) {
      log.setForcingSeparateLogging( forcingSeparateLogging );
    }
  }

  @Override
  public IMetaStore getMetaStore() {
    return metaStore;
  }

  @Override
  public void setMetaStore( IMetaStore metaStore ) {
    this.metaStore = metaStore;
  }

  @Override
  public int getCurrentOutputRowSetNr() {
    return currentOutputRowSetNr;
  }

  @Override
  public void setCurrentOutputRowSetNr( int index ) {
    currentOutputRowSetNr = index;
  }

  @Override
  public int getCurrentInputRowSetNr() {
    return currentInputRowSetNr;
  }

  @Override
  public void setCurrentInputRowSetNr( int index ) {
    currentInputRowSetNr = index;
  }

  @Override
  public Map<String, Object> getExtensionDataMap() {
    return extensionDataMap;
  }

  private class DefaultRowHandler implements RowHandler {
    @Override public Object[] getRow() throws HopException {
      return handleGetRow();
    }

    @Override public void putRow( RowMetaInterface rowMeta, Object[] row ) throws HopStepException {
      handlePutRow( rowMeta, row );
    }

    @Override public void putError( RowMetaInterface rowMeta, Object[] row, long nrErrors, String errorDescriptions,
                                    String fieldNames, String errorCodes ) throws HopStepException {
      handlePutError( rowMeta, row, nrErrors, errorDescriptions, fieldNames, errorCodes );
    }

    @Override public Object[] getRowFrom( RowSet rowSet ) throws HopStepException {
      return handleGetRowFrom( rowSet );
    }

    @Override public void putRowTo( RowMetaInterface rowMeta, Object[] row, RowSet rowSet ) throws HopStepException {
      handlePutRowTo( rowMeta, row, rowSet );
    }

  }


}
