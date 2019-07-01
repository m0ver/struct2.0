package tinystruct.examples;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.tinystruct.AbstractApplication;
import org.tinystruct.ApplicationException;
import org.tinystruct.data.component.Builder;
import org.tinystruct.system.ApplicationManager;

public class talk extends AbstractApplication {

  private static final long TIMEOUT = 10000;
  protected static final int DEFAULT_MESSAGE_POOL_SIZE = 10;
  protected final Map<String, BlockingQueue<Builder>> meetings = new ConcurrentHashMap<String, BlockingQueue<Builder>>();
  protected final Map<String, Queue<Builder>> list = new ConcurrentHashMap<String, Queue<Builder>>();
  protected final Map<String, List<String>> sessions = new ConcurrentHashMap<String, List<String>>();
  private ExecutorService service;
  private final Lock lock = new ReentrantLock();
  private final Condition consumer = lock.newCondition();

  @Override
  public void init() {
    this.setAction("talk/update", "update");
    this.setAction("talk/save", "save");
    this.setAction("talk/version", "version");
    this.setAction("talk/testing", "testing");

    if (this.service != null) {
      Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
        @Override
        public void run() {
          service.shutdown();
          while (true) {
            try {
                System.out.println("Waiting for the service to terminate...");
                if (service.awaitTermination(5, TimeUnit.SECONDS)) {
                  System.out.println("Service will be terminated soon.");
                  break;
                }
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          }
        }
      }));
    }
  }

  /**
   * To be used for testing.
   * @param meetingCode
   * @param sessionId
   * @param message
   * @return
   */
  public String save(Object meetingCode, String sessionId, String message) {
    if ( meetingCode != null ) {
      if (message != null && !message.isEmpty()) {
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-M-d h:m:s");
        final Builder builder = new Builder();
        builder.put("user", "user_"+sessionId);
        builder.put("time", format.format(new Date()));
        builder.put("message", filter(message));
        builder.put("session_id", sessionId);

        return this.save(meetingCode, builder);
      }
    }

    return "{}";
  }

  /**
   * Save message and create a thread for copying it to message list of each session.
   * @param meetingCode
   * @param builder
   * @return builder
   */
  public final String save(final Object meetingCode, final Builder builder) {
    if ((this.meetings.get(meetingCode)) == null) {
      this.meetings.put(meetingCode.toString(), new ArrayBlockingQueue<Builder>(DEFAULT_MESSAGE_POOL_SIZE));
    }

    try {
      this.meetings.get(meetingCode).put(builder);
      
      final BlockingQueue<Builder> messages = this.meetings.get(meetingCode);
      this.getService().execute(new Runnable(){
        @Override
        public void run() {
          Builder message;
          if ((message = messages.poll()) == null) return;
          copy(meetingCode, message);
        }
      });
      return builder.toString();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    return "{}";
  }

  private ExecutorService getService() {
    return this.service!=null? this.service : new ThreadPoolExecutor(0, 10, TIMEOUT, TimeUnit.MILLISECONDS, new SynchronousQueue<Runnable>());
  }

  /**
   * Poll message from the messages of the session specified sessionId.
   * @param sessionId
   * @return message
   * @throws ApplicationException
   * @throws IOException
   */
  public final String update(final String sessionId) throws ApplicationException, IOException {
    Builder message;
    Queue<Builder> messages = this.list.get(sessionId);
    // If there is a new message, then return it directly
    if((message = messages.poll()) != null) return message.toString();
    long startTime = System.currentTimeMillis();
    while((message = messages.poll()) == null && ( System.currentTimeMillis() - startTime) <= TIMEOUT) {
      // If waited less than 10 seconds, then continue to wait
      lock.lock();
      try {
        consumer.await(TIMEOUT, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        throw new ApplicationException(e.getMessage(), e);
      } finally {
        lock.unlock();
      }
    }
    
    return message != null ? message.toString() : "{}";
  }

  /**
   * Copy message to the list of each session.
   * @param meetingCode 
   * @param builder
   */
  private final void copy(Object meetingCode, Builder builder) {
    final List<String> _sessions;

    if((_sessions = this.sessions.get(meetingCode)) != null) {
      final Collection<Entry<String, Queue<Builder>>> set = this.list.entrySet();
      final Iterator<Entry<String, Queue<Builder>>> iterator = set.iterator();
      lock.lock();
      try {
        while(iterator.hasNext()) {
          Entry<String, Queue<Builder>> list = iterator.next();
          if(_sessions.contains(list.getKey())) {
            list.getValue().add(builder);
            consumer.signalAll();
          }
        }
      } finally {
        lock.unlock();
      }
    }
  }
  
  /**
   * Wake up those threads are working in message update.
   */
  final protected void wakeup(){
    lock.lock();
    try {
      consumer.signalAll();
    }
    finally {
      lock.unlock();
    }
  }

  /**
   * This function can be override.
   * @param text
   * @return
   */
  protected String filter(String text) {
    return text;
  }

  @Override
  public String version() {
    return "Talk core version:1.0 stable; Released on 2017-07-24";
  }

  /**
   * This is a testing. It can be executed with the command:
   * $ bin/dispatcher --import-applications=tinystruct.examples.talk talk/testing/100
   * 
   * @param n
   * @return
   * @throws ApplicationException
   */
  public boolean testing(final int n) throws ApplicationException {
    this.meetings.put("[M001]", new ArrayBlockingQueue<Builder>(DEFAULT_MESSAGE_POOL_SIZE));
    this.list.put("{A}", new ArrayDeque<Builder>());
    this.list.put("{B}", new ArrayDeque<Builder>());
    
    List<String> sess = new ArrayList<String>();
    sess.add("{A}");
    sess.add("{B}");
    this.sessions.put("[M001]", sess);
    
    this.getService().execute(new Runnable(){
      @Override
      public void run() {
        System.out.println(Thread.currentThread().getId());
        int i=0;
        while(i++<n)
        try {
          ApplicationManager.call("talk/save/[M001]/{A}/A post "+i, null);
          Thread.sleep(1);
        } catch (ApplicationException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        } catch (InterruptedException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    });

    this.getService().execute(new Runnable(){
      @Override
      public void run() {
        System.out.println(Thread.currentThread().getId());
        int i=0;
        while(i++<n)
        try {
          ApplicationManager.call("talk/save/[M001]/{B}/B post "+i, null);
          Thread.sleep(1);
        } catch (ApplicationException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        } catch (InterruptedException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    });

    this.getService().execute(new Runnable(){
      @Override
      public void run() {
        System.out.println(Thread.currentThread().getId());
        // TODO Auto-generated method stub
        System.out.println("[A] is started...");
        while(true)
        try {
          System.out.println("**A**:"+ApplicationManager.call("talk/update/{A}", null));
          Thread.sleep(1);
        } catch (ApplicationException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        } catch (InterruptedException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    });

    this.getService().execute(new Runnable(){
      @Override
      public void run() {
        System.out.println(Thread.currentThread().getId());
        // TODO Auto-generated method stub
        System.out.println("[B] is started...");
        while(true)
        try {
          System.out.println("**B**:"+ApplicationManager.call("talk/update/{B}", null));
          Thread.sleep(1);
        } catch (ApplicationException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        } catch (InterruptedException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    });
    
    return true;
  }

}
