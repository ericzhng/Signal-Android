package org.thoughtcrime.securesms.jobmanager;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public class JobManager {

  private static final Constraints NETWORK_CONSTRAINT = new Constraints.Builder()
                                                                       .setRequiredNetworkType(NetworkType.CONNECTED)
                                                                       .build();

  private static final Executor executor = Executors.newSingleThreadExecutor();

  public void add(Job job) {
    executor.execute(() -> {
      job.onAdded();

      Data.Builder dataBuilder = new Data.Builder();
      dataBuilder.putInt(Job.KEY_RETRY_COUNT, getRetryCount(job));
      dataBuilder.putLong(Job.KEY_RETRY_UNTIL, getRetryUntil(job));
      Data data = job.serialize(dataBuilder);

      // TODO: Need to take mastersecret and sqlcipher requirements into account, add them as fields in the data and manually check beforehand

      OneTimeWorkRequest.Builder requestBuilder = new OneTimeWorkRequest.Builder(job.getClass()).setInputData(data);

      if (requiresNetwork(job)) {
        requestBuilder.setConstraints(NETWORK_CONSTRAINT);
      }

      String groupId = getGroupId(job);
      if (groupId != null) {
        WorkManager.getInstance().beginUniqueWork(groupId, ExistingWorkPolicy.APPEND, requestBuilder.build()).enqueue();
      } else {
        WorkManager.getInstance().beginWith(requestBuilder.build()).enqueue();
      }
    });
  }

  private int getRetryCount(Job job) {
    // TODO: In general, maybe better to add these back as methods on Job?
    if (job.getJobParameters() != null) {
      return job.getJobParameters().getRetryCount();
    }
    return 0;
  }

  private long getRetryUntil(Job job) {
    if (job.getJobParameters() != null) {
      return job.getJobParameters().getRetryUntil();
    }
    return 0;
  }

  private boolean requiresNetwork(Job job) {
    // TODO: Remove network requirements and add field to jobparams
    // TODO: Same with other requirements -- we just want to remove them
    return true;
  }

  private String getGroupId(Job job) {
    if (job.getJobParameters() != null) {
      return job.getJobParameters().getGroupId();
    }
    return null;
  }

}
