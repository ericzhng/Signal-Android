package org.thoughtcrime.securesms.jobmanager;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.jobmanager.dependencies.ContextDependent;
import org.thoughtcrime.securesms.jobs.requirements.MasterSecretRequirement;
import org.thoughtcrime.securesms.jobs.requirements.SqlCipherMigrationRequirement;
import org.thoughtcrime.securesms.logging.Log;

import androidx.work.Data;
import androidx.work.Worker;

public abstract class Job extends Worker {

  private static final String TAG = Job.class.getSimpleName();

  static final String KEY_RETRY_COUNT            = "Job_retry_count";
  static final String KEY_RETRY_UNTIL            = "Job_retry_until";
  static final String KEY_REQUIRES_MASTER_SECRET = "Job_requires_master_secret";
  static final String KEY_REQUIRES_SQLCIPHER     = "Job_requires_sqlcipher";

  private final JobParameters jobParameters;

  protected Job(@Nullable JobParameters jobParameters) {
    this.jobParameters = jobParameters;
  }

  @NonNull
  @Override
  public Result doWork() {
    Data data = getInputData();

    ApplicationContext.getInstance(getApplicationContext()).injectDependencies(this);
    if (this instanceof ContextDependent) {
      ((ContextDependent)this).setContext(getApplicationContext());
    }
    initialize(data);

    try {
      if (withinRetryLimits(data)) {
        if (requirementsMet(data)) {
          onRun();
          return Result.SUCCESS;
        } else {
          return retry();
        }
      } else {
        Log.w(TAG, "Failing a job that hit its retry limit. Class: " + getClass().getSimpleName());
        return cancel();
      }
    } catch (Exception e) {
      if (onShouldRetry(e)) {
        return retry();
      }
      Log.w(TAG, "Received an exception that caused a job to fail. Class: " + getClass().getSimpleName(), e);
      return cancel();
    }
  }

  @Override
  public void onStopped(boolean cancelled) {
    onCanceled();
  }

  /**
   * All instance state needs to be persisted in the provided {@link Data.Builder} so that it can
   * be restored in {@link #initialize(Data)}.
   * @param dataBuilder The builder where you put your state.
   * @return The result of {@code dataBuilder.build()}.
   */
  protected Data serialize(Data.Builder dataBuilder) {
    return dataBuilder.build();
  }

  /**
   * Called after a run has finished and we've determined a retry is required, but before the next
   * attempt is run.
   */
  protected void onRetry() { }

  /**
   * Called after a job has been added to the JobManager queue. Invoked off the main thread, so its
   * safe to do longer-running work. However, work should finish relatively quickly, as it will
   * block the submission of future tasks.
   */
  protected void onAdded() { }

  /**
   * Restore all of your instance state from the provided {@link Data}. It should contain all of
   * the data put in during {@link #serialize(Data.Builder)}.
   * @param data Where your data is stored.
   */
  protected void initialize(Data data) { }

  /**
   * Called to actually execute the job.
   * @throws Exception
   */
  public abstract void onRun() throws Exception;

  /**
   * Called if a job fails to run (onShouldRetry returned false, or the number of retries exceeded
   * the job's configured retry count.
   */
  protected abstract void onCanceled();

  /**
   * If onRun() throws an exception, this method will be called to determine whether the
   * job should be retried.
   *
   * @param exception The exception onRun() threw.
   * @return true if onRun() should be called again, false otherwise.
   */
  protected abstract boolean onShouldRetry(Exception exception);

  @Nullable JobParameters getJobParameters() {
    return jobParameters;
  }

  private Result cancel() {
    onCanceled();
    return Result.FAILURE;
  }

  private Result retry() {
    onRetry();
    return Result.RETRY;
  }

  private boolean requirementsMet(Data data) {
    boolean met = true;

    if (data.getBoolean(KEY_REQUIRES_MASTER_SECRET, false)) {
      met &= new MasterSecretRequirement(getApplicationContext()).isPresent();
    }

    if (data.getBoolean(KEY_REQUIRES_SQLCIPHER, false)) {
      met &= new SqlCipherMigrationRequirement(getApplicationContext()).isPresent();
    }

    return met;
  }

  private boolean withinRetryLimits(Data data) {
    int  retryCount = data.getInt(KEY_RETRY_COUNT, 0);
    long retryUntil = data.getLong(KEY_RETRY_UNTIL, 0);

    if (retryCount > 0) {
      return getRunAttemptCount() <= retryCount;
    }

    return System.currentTimeMillis() < retryUntil;
  }
}
