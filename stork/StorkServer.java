package stork;

import stork.*;
import stork.util.*;
import stork.module.*;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

// TODO: Search FIXME and TODO!

public class StorkServer implements Runnable {
  // Server state variables
  private static ServerSocket listen_sock;

  private static Thread[] thread_pool;

  private static Map<String, StorkCommand> cmd_handlers;
  private static Map<String, TransferModule> xfer_modules;

  private static LinkedBlockingQueue<StorkJob> queue;
  private static ArrayList<StorkJob> all_jobs;

  // Used to determine time relative to start of server.
  private static long server_date = System.currentTimeMillis();
  private static long server_mt_base = System.nanoTime() / (long)1E6;

  // Configuration variables
  private boolean daemon = false;
  private static StorkConfig conf = new StorkConfig();

  // The only fields allowed in a submit ad
  // FIXME: sucks
  private static String[] submit_filter = {
    "src_url", "dest_url", "dap_type", "x509_proxy", "max_attempts",
    "parallelism", "max_parallelism", "min_parallelism", "arguments"
  };

  // States a job can be in.
  static enum JobStatus {
    scheduled, processing, removed, failed, complete
  }

  // Filters for jobs of certain types.
  static class JobFilter {
    static EnumSet<JobStatus>
      all = EnumSet.allOf(JobStatus.class),
      pending = EnumSet.of(JobStatus.scheduled,
                           JobStatus.processing),
      done = EnumSet.complementOf(JobFilter.pending);
  }

  // Some static initializations...
  static {
    // Initialize command handlers
    cmd_handlers = new HashMap<String, StorkCommand>();
    cmd_handlers.put("stork_q", new StorkQHandler());
    cmd_handlers.put("stork_list", new StorkQHandler());
    cmd_handlers.put("stork_submit", new StorkSubmitHandler());
    cmd_handlers.put("stork_rm", new StorkRmHandler());
    cmd_handlers.put("stork_info", new StorkInfoHandler());

    // Initialize transfer module set
    xfer_modules = new HashMap<String, TransferModule>();

    // Initialize queues
    queue = new LinkedBlockingQueue<StorkJob>();
    all_jobs = new ArrayList<StorkJob>();
  }

  // Inner classes
  // -------------
  // A representation of a job submitted to Stork. When this job is
  // run by a thread, start the transfer, and read aux_ads from it
  // until the job completes.
  // TODO: Make this less hacky with the filters and such.
  static class StorkJob implements Runnable {
    JobStatus status;
    ClassAd job_ad, aux_ad;
    StorkTransfer transfer;
    TransferModule tm;

    int job_id = 0;
    int attempts = 0, rv = -1;
    String message = null;
    long submit_time, start_time = -1;
    int run_duration = -1;

    // Set me to null to force regeneration of info ad by getAd().
    ClassAd cached_ad = null;

    // Create a StorkJob from a job ad.
    // TODO: Replace with JobAd special ad.
    public StorkJob(ClassAd ad) {
      job_ad = new ClassAd(ad);
      set_status(JobStatus.scheduled);

      URI src_url, dest_url;
      String sp, dp;

      // Sanitize URLs
      try {
        src_url  = new URI(ad.get("src_url"));
        dest_url = new URI(ad.get("dest_url"));
        job_ad.insert("src_url", src_url.toString());
        job_ad.insert("dest_url", dest_url.toString());
      } catch (Exception e) {
        set_status(JobStatus.failed);
        set_message("could not parse src_url or dest_url");
        return;
      }

      // Check if transfer module exists for job.
      sp = src_url.getScheme();
      dp = dest_url.getScheme();
      tm = xfer_modules.get(sp);

      if (tm == null || tm != xfer_modules.get(dp)) {
        set_status(JobStatus.failed);
        set_message("could not find transfer module for "+sp+" -> "+dp);
      }

      // Set the submit time for the job
      submit_time = get_server_time();
    }

    // Gets the job info as a ClassAd. Will return cached_ad if there is one.
    // To force regeneration of ad, reset cached_ad to null.
    public synchronized ClassAd getAd() {
      ClassAd ad = cached_ad;

      // If we don't have a cached ad, generate a new one.
      if (ad == null) {
        ad = new ClassAd(job_ad);

        // Remove sensitive stuff.
        ad.remove("x509_proxy");

        // Merge auxilliary ad if there is one.
        // TODO: Real filtering.
        if (aux_ad != null) {
          ad.importAd(aux_ad);
          ad.remove("response");  // Don't misbehave!
        }

        // Add other job information.
        ad.insert("status", status.toString());

        if (job_id > 0)
          ad.insert("job_id", job_id);
        if (attempts > 0)
          ad.insert("attempts", attempts);
        if (message != null)
          ad.insert("message", message);
        if (run_duration >= 0)
          ad.insert("run_duration", pretty_time(run_duration));
      }

      // Add elapsed time if processing
      if (status == JobStatus.processing)
        ad.insert("run_duration", pretty_time(since(start_time)));
      
      return cached_ad = ad;
    }

    // Given date in ms, return ms elapsed.
    // XXX: Kinda hacky to have this here...
    private static int since(long t) {
      if (t < 0) return -1;
      return (int) (get_server_time() - t);
    }

    // Given a duration in ms, return a pretty string representation.
    private static String pretty_time(int t) {
      if (t < 0) return null;

      int i = t % 1000,
          s = (t/=1000) % 60,
          m = (t/=60) % 60,
          h = (t/=60) % 24,
          d = t / 24;

      return (d > 0) ? String.format("%dd%02dh%02dm%02ds", d, h, m, s) :
             (h > 0) ? String.format("%dh%02dm%02ds", h, m, s) :
             (m > 0) ? String.format("%dm%02ds", m, s) :
                       String.format("%d.%02ds", s, i/10);
    }

    // Sets the status of the job and updates the ClassAd accordingly.
    public synchronized void set_status(JobStatus s) {
      status = s;

      if (cached_ad != null)
        cached_ad.insert("status", s.toString());
    }

    // Set job message. Pass null to remove message.
    public synchronized void set_message(String m) {
      message = m;

      if (cached_ad != null) {
        if (m != null)
          cached_ad.insert("message", m);
        else
          cached_ad.remove("message");
      }
    }

    // Set the attempts counter.
    public synchronized void set_attempts(int a) {
      attempts = a;

      if (cached_ad != null) {
        if (a != 0)
          cached_ad.insert("attempts", a);
        else
          cached_ad.remove("attempts");
      }
    }

    // Called when the job gets removed. Returns true if the job had its
    // state updated, and false otherwise (e.g., the job was already
    // complete or couldn't be removed).
    public synchronized boolean remove(String reason) {
      switch (status) {
        // Try to stop the job. If we can't, don't do anything.
        case processing:
          if (transfer != null) transfer.stop();
          run_duration = since(start_time);
          transfer = null;

        // Fall through to set removed status.
        case scheduled:
          set_message(reason);
          set_status(JobStatus.removed);

          return true;

        // In any other case, the job has ended, do nothing.
        default:
          return false;
      }
    }

    // Check if the job should be rescheduled.
    public synchronized boolean shouldReschedule() {
      // Check for forced rescheduling prevention.
      if (rv >= 255)
        return false;

      // Check for custom max attempts.
      int max = job_ad.getInt("max_attempts", 10);
      if (max > 0 && attempts >= max)
        return false;

      // Check for configured max attempts.
      max = conf.getInt("max_attempts", 10);
      if (max > 0 && attempts >= max)
        return false;

      return true;
    }

    // Run the job and watch it to completion.
    public void run() {
      // Must be scheduled to be able to run.
      if (status != JobStatus.scheduled)
        return;

      // Start transfer
      synchronized (status) {
        start_time = get_server_time();
        set_status(JobStatus.processing);
        transfer = tm.transfer(job_ad);
        transfer.start();
      }

      // Read progress ads until end of ad stream.
      while (true) {
        ClassAd ad = transfer.getAd();

        if (ad == null || ad.error()) break;

        aux_ad = ad;

        // Check if we have message; unset if present and empty string.
        if (aux_ad.has("message"))
          message = aux_ad.get("message");
        if (message != null && message.isEmpty())
          message = null;

        cached_ad = null;  // Blow out the cached ad.
      }

      // Wait for job to complete and get exit status.
      rv = transfer.waitFor();
      transfer = null;
      run_duration = since(start_time);

      if (rv == 0) {  // Job successful!
        set_status(JobStatus.complete);
        transfer = null;
        return;
      }

      // Job not successful! :( Check if we should requeue.
      if (shouldReschedule()) {
        set_status(JobStatus.scheduled);

        set_attempts(attempts+1);
        System.out.println("Job "+job_id+" failed! Rescheduling...");
        transfer = null;

        try {
          queue.put(this);
        } catch (Exception e) {
          System.out.println("Error rescheduling job "+job_id+": "+e);
        }
      } else {
        System.out.println("Job "+job_id+" failed!");
        transfer = null;
        set_status(JobStatus.failed);
        set_attempts(attempts);
      }
    }
  }

  // A thread which runs continuously and starts jobs as they're found.
  private static class StorkQueueThread extends Thread {
    // Continually remove jobs from the queue and start them.
    public void run() {
      System.out.println("Thread "+getId()+": starting");
      
      while (true) try {
        StorkJob job = queue.take();
        System.out.print("Thread "+getId()+": ");
        System.out.println("Pulled job from queue");

        // Some sanity checking
        if (job.status != JobStatus.scheduled) {
          System.out.println("How did this get here?! ("+job+")");
          continue;
        }

        job.run();
      } catch (Exception e) {
        System.out.print("Thread "+getId()+": ");
        System.out.println("Something bad happened in StorkQueueThread...");
        e.printStackTrace();
      }
    }
  }

  // Stork command handlers should implement this interface
  private static interface StorkCommand {
    public ResponseAd handle(OutputStream s, ClassAd ad);
  }

  private static class StorkQHandler implements StorkCommand {
    public ResponseAd handle(OutputStream s, ClassAd ad) {
      // The list with ultimately read result from.
      Iterable<StorkJob> list = all_jobs;
      EnumSet<JobStatus> filter = null;
      String type = ad.get("status");
      Range range, nfr = new Range();
      int count = 0;
      boolean missed = false;

      // Lowercase the job type just to make things easier.
      if (type != null)
        type = type.toLowerCase();

      // Pick a filter depending on type. Valid types include:
      // "pending", "done", "all", and any job status. Defaults to
      // "pending" if no range specified, "all" if it is specified.
      if (type == null) {
        filter = ad.has("range") ? JobFilter.all : JobFilter.pending;
      } else if (type.equals("pending")) {
        filter = JobFilter.pending;
      } else if (type.equals("done")) {
        filter = JobFilter.done;
      } else if (type.equals("all")) {
        filter = JobFilter.all;
      } else try {
        filter = EnumSet.of(JobStatus.valueOf(type));
      } catch (Exception e) {
        return new ResponseAd("error", "invalid job type '"+type+"'");
      }

      // If a range was given, show jobs from range.
      if (ad.has("range"))
        range = Range.parseRange(ad.get("range"));
      else
        range = new Range(1, all_jobs.size());
      if (range == null)
        return new ResponseAd("error", "could not parse range");

      // Show all jobs in range matching filter.
      for (int i : range) try {
        StorkJob j = all_jobs.get(i-1);

        if (!filter.contains(j.status))
          continue; 
        count++;

        System.out.println("Sending :"+j.getAd());
        s.write(j.getAd().getBytes());
        s.flush();
      } catch (IndexOutOfBoundsException oobe) {
        missed = true;
        nfr.swallow(i);
      } catch (Exception e) {
        return new ResponseAd("error", e.getMessage());
      }

      // Inform user of count and any missing jobs.
      ResponseAd res = new ResponseAd("success");

      if (!nfr.isEmpty()) {
        if (count == 0)
          res.set("error", "no jobs found");
        else
          res.insert("not_found", nfr.toString());
      }

      res.insert("count", count);
      return res;
    }
  }

  private static class StorkSubmitHandler implements StorkCommand {
    public ResponseAd handle(OutputStream s, ClassAd ad) {
      // Filter unexpected submit ad attributes.
      ad = ad.filter(submit_filter);

      // Validate ad
      String src  = ad.get("src_url");
      String dest = ad.get("dest_url");
      String type = ad.get("dap_type");
      URI src_url, dest_url;

      if (src == null)
        return new ResponseAd("error", "missing src_url");
      if (dest == null)
        return new ResponseAd("error", "missing dest_url");

      // Parse source and dest URLs
      // TODO: Report which URL couldn't be parsed and why
      try {
        src_url  = new URI(src);
        dest_url = new URI(dest);
      } catch (Exception e) {
        return new ResponseAd("error", "error parsing URLs");
      }

      // Check that requested protocols are supported
      String sp = src_url.getScheme(), dp = dest_url.getScheme();

      if (sp == null || !xfer_modules.containsKey(sp))
        return new ResponseAd("error", "src_url protocol not supported");
      if (dp == null || !xfer_modules.containsKey(dp))
        return new ResponseAd("error", "dest_url protocol not supported");

      StorkJob job = new StorkJob(ad);

      // Check that is ready to be processed.
      if (job.status != JobStatus.scheduled)
        return new ResponseAd("error", job.message);

      // Add job to the job log and determine job id
      synchronized (all_jobs) {
        all_jobs.add(job);
        job.job_id = all_jobs.size();
      }

      // Add to the scheduler
      try {
        queue.put(job);
      } catch (Exception e) {
        System.out.println("Error scheduling job "+job.job_id+": "+e);
      }

      ResponseAd res = new ResponseAd("success");
      res.insert("job_id", job.job_id);
      return res;
    }
  }

  private static class StorkRmHandler implements StorkCommand {
    public ResponseAd handle(OutputStream s, ClassAd ad) {
      StorkJob j;
      String reason = "removed by user";
      Range r, cdr = new Range();

      if (!ad.has("range"))
        return new ResponseAd("error", "no job_id specified");

      r = Range.parseRange(ad.get("range"));

      if (r == null)
        return new ResponseAd("could not parse range");

      if (ad.has("reason"))
        reason = reason+" ("+ad.get("reason")+")";

      // Find ad in job list, set it as removed.
      for (int job_id : r) try {
        j = all_jobs.get(job_id-1);
        queue.remove(j);
        j.remove(reason);
      } catch (IndexOutOfBoundsException oobe) {
        cdr.swallow(job_id);
      } catch (Exception e) {
        return new ResponseAd("error", e.getMessage());
      }

      return new ResponseAd("success");
    }
  }

  private static class StorkInfoHandler implements StorkCommand {
    // Send transfer module information
    ResponseAd sendModuleInfo(OutputStream s) {
      for (TransferModule tm : xfer_modules.values()) {
        try {
          s.write(tm.info_ad().getBytes());
          s.flush();
        } catch (Exception e) {
          return new ResponseAd("error", e.getMessage());
        }
      } return new ResponseAd("success");
    }

    // TODO: Send server information.
    ResponseAd sendServerInfo(OutputStream s) {
      return new ResponseAd("error", "not yet implemented");
    }

    public ResponseAd handle(OutputStream s, ClassAd ad) {
      String type = ad.get("type", "module");

      if (type.equals("module"))
        return sendModuleInfo(s);
      if (type.equals("server"))
        return sendServerInfo(s);
      return new ResponseAd("error", "invalid type: "+type);
    }
  }

  // Class methods
  // -------------
  // TODO Replace with handler thread to prevent DoS attacks
  private void handle_client(Socket s) throws IOException {
    InputStream is;
    OutputStream os;
    ClassAd ad;
    ResponseAd res = null;
    String cmd;

    System.out.println("Got connection: "+s.toString());

    // Read ClassAds until the client disconnects.
    while (s.isConnected()) {
      is = s.getInputStream();
      os = s.getOutputStream();

      // Read a ClassAd
      System.out.println("Waiting for ClassAd...");
      ad = ClassAd.parse(is);

      if (ad == ClassAd.EOF) {
        System.out.println("End of stream reached...");
        return;
      }

      // If we get a bad ad, disconnect
      if (ad == ClassAd.ERROR) {
        System.out.println("Got bad ad...");
        s.close();
        return;
      }

      System.out.println("Got ClassAd: "+ad);

      cmd = ad.get("command");

      // If ad doesn't contain a command, ignore it
      if (cmd == null) {
        os.write(new ResponseAd("error", "no command given").getBytes());
        os.flush();
        continue;
      }

      ad.remove("command");

      // Look up handler for command
      StorkCommand cmd_handler = cmd_handlers.get(cmd);

      if (cmd_handler != null)
        res = cmd_handler.handle(os, ad);
      else
        res = new ResponseAd("error", "unsupported command '"+cmd+"'");

      if (res == null)
        res = new ResponseAd("success");

      System.out.println("Responding with ad: "+res);

      // Write response ad and flush
      os.write(res.getBytes());
      os.flush();
    } s.close();
  }

  public void register_module(TransferModule tm) {
    for (String p : tm.protocols()) {
      // Check if already registered.
      if (xfer_modules.containsKey(p)) {
        System.out.println("Note: protocol "+p+" already registered, not registering");
        continue;
      }

      System.out.println("Registering protocol: "+p+" ("+tm+")");
      xfer_modules.put(p, tm);
    }
  }

  // Iterate over libexec directory and add transfer modules to list.
  public void populate_modules() {
    File libexec = new File(conf.get("libexec"));

    if (!libexec.isDirectory()) {
      System.out.println("Error: libexec is not a directory!");
      return;
    }

    // Load built-in modules.
    register_module(new StorkGridFTPModule());

    // Iterate over and populate external module list.
    for (File f : libexec.listFiles()) {
      // Skip over things that obviously aren't transfer modules.
      if (f.isDirectory() || f.isHidden() || !f.canExecute())
        continue;

      TransferModule tm = ExternalModule.create(f);

      if (tm != null)
        register_module(tm);
    }

    // Check if anything got added.
    if (xfer_modules.isEmpty()) {
      System.out.println("Warning: no transfer modules registered");
    }
  }

  // Get the current Unix date in ms based on server time.
  static long get_server_time() {
    long dtime = System.nanoTime() / (long)1E6 - server_mt_base;
    return server_date + dtime;
  }

  // Entry point for the server
  public void run() {
    // Populate module list
    populate_modules();

    // Initialize thread pool based on config.
    int tnum = conf.getInt("max_jobs", -1);

    if (tnum < 1) {
      tnum = 10;
      System.out.println("Warning: invalid value for max_jobs, "+
                         "defaulting to "+tnum);
    }

    thread_pool = new Thread[tnum];
    
    for (int i = 0; i < thread_pool.length; i++) {
      thread_pool[i] = new StorkQueueThread();
      thread_pool[i].start();
    }

    // Listen for new connections on the socket
    while (true) try {
      handle_client(listen_sock.accept());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  // Constructor
  // -----------
  public StorkServer(InetAddress host, int port) throws IOException {
    // Create a listening socket
    if (host != null)
      listen_sock = new ServerSocket(port, 0, host);
    else
      listen_sock = new ServerSocket(port);

    System.out.printf("Listening on %s:%d...\n",
                      listen_sock.getInetAddress().getHostAddress(),
                      listen_sock.getLocalPort());
  }

  public StorkServer(int p) throws IOException {
    this(null, p);
  }

  public StorkServer() throws IOException {
    this(null, 0);
  }

  // Main entry point
  public static void main(String[] args) {
    StorkServer server;

    // Populate module list
    try {
      // Check for passed config location

      // Parse config file
      conf.parseConfig();

      // Parse other arguments
      if (args.length > 1 && args[1].equals("-d")) {
        System.out.close();
      }

      // TODO: Load server state from old state file if one exists.

      // Create instance of Stork server and run it
      server = new StorkServer(conf.getInt("port"));
      server.run();
    } catch (Exception e) {
      System.out.println("Error: "+e.getMessage());
      System.exit(1);
    }
  }
}