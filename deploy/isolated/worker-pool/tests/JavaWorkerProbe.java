import com.haitlify.aitest.platform.assistant.hermes.HermesTcpGateway;
import com.haitlify.aitest.platform.assistant.runtime.HermesAssistantRuntime;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
public class JavaWorkerProbe {
 public static void main(String[] args) throws Exception {
  String mode=args[1]; var mapper=new ObjectMapper();
  var gateway=new HermesTcpGateway("127.0.0.1",Integer.parseInt(args[0]),4,mapper);
  var runtime=new HermesAssistantRuntime(gateway,mapper);
  if (mode.equals("cwd")) {
   try { gateway.createSession("/state"); throw new AssertionError("state accepted"); }
   catch (IllegalStateException expected) { System.out.println("STATE_CWD_REJECTED"); } return;
  }
  var s=runtime.submit("/workspace",mode.equals("interrupt")?"block":mode);
  if(s.state()!=HermesAssistantRuntime.State.SUBMITTED || s.storedSessionId()!=null || s.turnRef()!=null) throw new AssertionError(s);
  System.out.println("ACK_SUBMITTED_UNKNOWN_DURABLE_TURN");
  if(mode.equals("interrupt")){Thread.sleep(200);if(!gateway.interrupt(s.sessionId()))throw new AssertionError("interrupt receipt rejected");System.out.println("INTERRUPT_ACK_TRUE");}
  try {
   String reply=runtime.awaitFinalText(s,Duration.ofSeconds(3));
   if(!mode.equals("hello"))throw new AssertionError("error promoted: "+reply);
   if(!reply.equals("verified reply"))throw new AssertionError(reply);
   System.out.println("FINAL_ACTUAL_GATEWAY_RUNTIME_OK");
  }catch(IllegalStateException expected){
   if(mode.equals("hello"))throw expected;
   System.out.println("NON_SUCCESS_FINAL_REJECTED "+mode);
  }
  try{gateway.submitPrompt(s.sessionId(),"hello");throw new AssertionError("duplicate accepted");}
  catch(IllegalStateException expected){System.out.println("DUPLICATE_REJECTED");}
 }
}
