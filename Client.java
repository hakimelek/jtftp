import java.io.*; 
import java.net.*; 
import java.util.Arrays;
import java.util.Scanner;
import org.apache.commons.net.io.*;

class Client {  
	
	static Client driver; 
	
    static InetAddress IPAddress;
    static String request = null;
    static String filename = null;
    static String IPaddress = null;
    static String mode = null; 
 
    static final byte RRQopcode[] = {(byte) 0, (byte) 1}; 
    static final byte WRQopcode[] = {(byte) 0, (byte) 2}; 
    static final byte DATAopcode[] = {(byte) 0, (byte) 3}; 
    static final byte ACKopcode[] = {(byte) 0, (byte) 4}; 
    static final byte ERRORopcode[] = {(byte) 0, (byte) 5}; 
     
    private final static int DATA_SIZE = 512;
    private final static int TFTP_DEFAULT_PORT = 69;
    private final static int NUMBER_OF_TRIES = 10;
    private final static int WAITTIME_TO_RETRY = 500;
    private final static int TIME_TO_RECEIVE = 30000;
    private final static int MAX_BLOCK_NUMBER = 65535;
    
    private Scanner reader = new Scanner(System.in);
    
    static DatagramSocket clientSocket;
    static DatagramPacket UDPPacket;
    static int TFPTport;
	
        public void run ()
        {
            String command = "";
            showMenu();
            
            while(!command.equals("quit"))
            {
                System.out.println("select: ['send' / 'receive'] ");
                command = getCommand();
                request = command;
                System.out.println("select:[filename]");
                command = getCommand();
                filename = command;
                System.out.println("select: [IPaddress] ");
                command = getCommand();
                IPaddress = command;
                System.out.println("select: [mode: netascii/octet]");
                command = getCommand();
                mode = command;
                mode = mode.toUpperCase();
                
                command = "quit";
            }
        }
        
        private void showMenu(){
            System.out.println("\tTFTP Client\nType: 'quit' to exit");    
        }
        
        private String getCommand(){
            System.out.print ("$ ");
            return reader.next();
        }
        
        public void transfer () throws IOException, InterruptedException{
        	 clientSocket = new DatagramSocket();   

             try {
                 IPAddress = InetAddress.getByName(IPaddress);
                 System.out.println(IPAddress);
             } 
             catch (UnknownHostException e) {
                    System.out.println(IPaddress + " is not a valid IP");
             }
             
             switch(request){
               case "receive": sendRRQ(filename, mode); break;
               case "send": sendWRQ(filename, mode); break;
               default: { System.out.println("false request type, try again!"); driver.run();}
             }
             
             clientSocket.close();
        }
 
     public static void main(String args[]) throws Exception{                     
         driver = new Client();
         driver.run();
         driver.transfer();
     } 
 
////////////////////////////// RECEIVE  /////////////////////////////

    public static void sendRRQ(String filename, String mode) throws IOException, InterruptedException{    
        System.out.println("init receiving "+filename+"...");       
        sendRequest("read", filename, mode);
        
    // receive first data             
        File newfile = new File(filename);          
        FileOutputStream receivedFileOctet = new FileOutputStream(newfile);
        FromNetASCIIOutputStream receivedFileNetascii = null;

        boolean done = false; 
        int block=1; 
        int times=0;
        
        clientSocket.setSoTimeout(TIME_TO_RECEIVE);
        
        // loop through this 
        while(!done){
        	byte[] receiveBuff = new byte[DATA_SIZE+4]; 
        	
        	UDPPacket = new DatagramPacket(receiveBuff, receiveBuff.length, IPAddress, clientSocket.getLocalPort());  
        	clientSocket.receive(UDPPacket);    
                     
            TFPTport = UDPPacket.getPort(); 
            
            int opcodereceived =  getInt(Arrays.copyOfRange(receiveBuff, 0, 2));

            if(opcodereceived==5){ // error
                
                System.out.println("ERROR: " +  new String(Arrays.copyOfRange(receiveBuff, 4 ,receiveBuff.length)));
                done = true; 
            	clientSocket.close();

            }
            else if(opcodereceived==3){ // data
            	if(!newfile.exists()){
                    newfile.createNewFile();
                }
            	
                int blockreceived = getInt(Arrays.copyOfRange(receiveBuff, 2, 4)); 
                byte[] datareceived = Arrays.copyOfRange(receiveBuff, 4, UDPPacket.getLength());
            	 
                
            	if(block==blockreceived){
            		 if(UDPPacket.getLength()>=DATA_SIZE){ // file still transfering
                         System.out.println("block: "+ block);
                         if(mode=="OCTET"){
                             receivedFileOctet.write(datareceived);
                         }
                         else if(mode.equals("NETASCII")){
                        	 receivedFileNetascii = new FromNetASCIIOutputStream(receivedFileOctet);
                        	 receivedFileNetascii.write(datareceived);

                         }      
                         sendAck(block);
                     }
                     else { //file is done transfering
                         System.out.println("(last) block: "+ block);

                         if(mode.equals("OCTET")){
                             receivedFileOctet.write(datareceived);
                             receivedFileOctet.close();

                         }
                         else if(mode.equals("NETASCII")){
                        	 receivedFileNetascii = new FromNetASCIIOutputStream(receivedFileOctet);
                        	 receivedFileNetascii.write(datareceived);
                             receivedFileNetascii.close();
                         }
                         
                         //send ack
                         sendAck(block);
                         done = true; 
                     }
            		
            		 block++; 
            		 
            		 if(block>MAX_BLOCK_NUMBER){ // block is of size 16 bits
                 		block=0; // reinit if bigger that max size
                   	 }
            	}
            	else if(block==blockreceived+1){
            		if(times<NUMBER_OF_TRIES){
                		Thread.sleep(WAITTIME_TO_RETRY);
            			sendAck(block-1);
            			times++;
            		}
            		else{
            			sendError(4);
                    	clientSocket.close();
            		}
            	}
            	else{
            		sendError(4); 
            	}  
            	                
            }    
        }
        receivedFileOctet.close();
                  
    }
        
////////////////////////////// SEND  /////////////////////////////
     
    public static void sendWRQ(String filename, String mode) throws IOException, InterruptedException{
        System.out.println("init sending " + filename+"...");
 
        File file = null;
        
         try{
            file = new File(filename);
         }
         catch(Exception e){
             e.printStackTrace();
         }
        
         byte[] filebytes = new byte[(int) file.length()];
         byte[] filebytesnetascii = null;
         
         FileInputStream outOctet = null;
         outOctet = new FileInputStream(file);
         
         try{
        	if(mode.equals("OCTET")){	
            	outOctet.read(filebytes);         
            }
            else if(mode.equals("NETASCII")){
            	ToNetASCIIInputStream outNetascii = new ToNetASCIIInputStream(outOctet);
            	filebytesnetascii = new byte[outNetascii.available()]; 
            	outNetascii.read(filebytesnetascii); 
            }

            System.out.println("File size ="+filebytes.length);
        }
        catch(Exception e){
            e.printStackTrace();
        }         
         
        sendRequest("write", filename, mode);
         
    // receive ack
        byte[] receiveBuff = new byte[4]; // max size 4 = opcode + block# 
 
        try{
          UDPPacket = new DatagramPacket(receiveBuff, receiveBuff.length, IPAddress, clientSocket.getLocalPort());         
          clientSocket.receive(UDPPacket);   
        }
        catch(Exception e){
            System.out.println("error receiving first ack packet with block# 0");
        }
         
        TFPTport = UDPPacket.getPort(); 
        
        int block = 0; 
 
        int opcodereceived =  getInt(Arrays.copyOfRange(receiveBuff, 0, 2));
        
        if(opcodereceived == 5){ // error
        	// timeout 
        	Thread.sleep(5000);
        	// send request again
            System.out.println("ERROR: " +  new String(Arrays.copyOfRange(receiveBuff, 4 ,receiveBuff.length)));
            System.out.println("RETRY SENDING REQUEST");
            sendRequest("write", filename, mode);
        }
        else if(opcodereceived==4){  // ack
            int blockreceived = getInt(Arrays.copyOfRange(receiveBuff, 2, 4)); 
            if(blockreceived==block){
            	if(mode.equals("OCTET")){
                	sendFile(filebytes, clientSocket, UDPPacket);                    
                }
                else if(mode.equals("NETASCII")){
                	sendFile(filebytesnetascii, clientSocket, UDPPacket);
                }
            }
            else{
            	sendError(4);
            	clientSocket.close();
            }
        }      
    }
    
////////////////////////////// UTILITIES  /////////////////////////////
     
    // Ack packet
    public static byte[] getAckPacket(int block) throws IOException{
        ByteArrayOutputStream outputPacket = new ByteArrayOutputStream( );
        
        outputPacket.write(ACKopcode);
        outputPacket.write(getByteArray(block));
         
        return outputPacket.toByteArray();  
    }
     
    // Data packet
    public static byte[] getDataPacket(int block, byte[] data) throws IOException{
        ByteArrayOutputStream outputPacket = new ByteArrayOutputStream( );
                      
        outputPacket.write(DATAopcode);
        outputPacket.write(getByteArray(block));
        outputPacket.write(data);
 
        return outputPacket.toByteArray();  
    }
     
    // Error packet
    public static byte[] getErrorPacket(int errorcode) throws IOException{
        ByteArrayOutputStream outputPacket = new ByteArrayOutputStream( );
         
        String errorMessage = null; 
         
        switch(errorcode){
          case 0: errorMessage="Not defined, see error message (if any)."; break;
          case 1: errorMessage="File not found"; break;
          case 2: errorMessage="Access Violation."; break;
          case 3: errorMessage="Disk full or allocation exceeded."; break;
          case 4: errorMessage="Illegal TFTP operation."; break;
          case 5: errorMessage="Unknown transfer ID"; break;
          case 6: errorMessage="File already exists."; break;
          case 7: errorMessage="No such user."; break;
        }
         
        outputPacket.write(ERRORopcode);
        outputPacket.write(getByteArray(errorcode));
        outputPacket.write(errorMessage.getBytes());
        outputPacket.write(ERRORopcode);
        outputPacket.write((byte) 0);
         
        return outputPacket.toByteArray();  
    }
     

    // request packet
    public static byte[] getRequestPacket(String type, String filename2, String mode) throws IOException{
        ByteArrayOutputStream outputPacket = new ByteArrayOutputStream( );
         
        if (type=="read"){
            outputPacket.write(RRQopcode);
        }
        else if(type=="write"){
            outputPacket.write(WRQopcode);
        }
        else{
        	System.out.println("Error in request type");
        }
        outputPacket.write( filename.getBytes() );
        outputPacket.write((byte) 0);
        outputPacket.write(mode.getBytes());
        outputPacket.write((byte) 0);
         
        return outputPacket.toByteArray();
    }
     
   // Convert int to byte array of size 2 
    public static byte[] getByteArray(int integer){
        byte[] array = new byte[2];
        array[1] = (byte) (integer & 0xFF);
        array[0] = (byte) ((integer >> 8) & 0xFF);
        return array;
    }
 
    // Convert byte array of size 2 to int
    public static int getInt(byte[] array){
        int integer; 
        integer = ((array[0] & 0xff) << 8) | (array[1] & 0xff);
        return integer;
    }
    
    //send Acknowledgement  
    public static void sendAck(int block) throws IOException{
    	 byte[] ackbuff = getAckPacket(block); 
    	 
         try{
              UDPPacket = new DatagramPacket(ackbuff, ackbuff.length, IPAddress, TFPTport);
              clientSocket.send(UDPPacket);    
          }
          catch(Exception e){
              System.out.println("error sending request packet");
          }
    }
    
    //send Error  
    public static void sendError(int errorcode) throws IOException{
    	 byte[] errorbuff = getErrorPacket(errorcode); 
    	 
         try{
         	UDPPacket = new DatagramPacket(errorbuff, errorbuff.length, IPAddress, TFPTport);
            clientSocket.send(UDPPacket);    
          }
          catch(Exception e){
              System.out.println("error sending request packet");
          }
    }
    
    //send Request
    public static void sendRequest(String requestType, String filename, String mode) throws IOException{
    	// Build write request  
        byte[] requestbuff = getRequestPacket(requestType, filename, mode); 
        
        // send write request
       try{
         	UDPPacket = new DatagramPacket(requestbuff, requestbuff.length, IPAddress, TFTP_DEFAULT_PORT);
            clientSocket.send(UDPPacket);    
        }
        catch(Exception e){
            System.out.println("error sending request packet " + e);
        }
    }
    
    //send File
    public static void sendFile(byte[] file, DatagramSocket clientSocket, DatagramPacket UDPPacket) throws IOException, InterruptedException{
          	    int block=0; 
                // start sending data 
                boolean done = false; 
                int counter=0;
                byte[] chunck; 
                
                while(!done){
                    block++; 
                    
                	if(block>65535){ // block is of size 16 bits
                		block=0; // reinit if bigger that max size
                	}
                	

                	if(file.length-counter>=DATA_SIZE){
                        chunck= Arrays.copyOfRange(file, counter, counter+DATA_SIZE);
                        System.out.println("block:" + block);

                	}
                	else { // last
                        chunck= Arrays.copyOfRange(file, counter, file.length);
                        System.out.println("(last) block:" + block);
                        done=true;
                	}

                    byte[] dataPacket = getDataPacket(block, chunck); 
                    
                    // Send at first
                    try{
                    	UDPPacket = new DatagramPacket(dataPacket, dataPacket.length, IPAddress, TFPTport);
                        clientSocket.send(UDPPacket);   
                     }
                     catch(Exception e){
                         System.out.println("error sending data packet");
                    }
                    
                    // Receiving ack 
                    byte[] ackPacket = new byte[4];
                      
                    try{
                    	UDPPacket = new DatagramPacket(ackPacket, ackPacket.length, IPAddress, TFPTport);
                        clientSocket.receive(UDPPacket);    
                     }
                     catch(Exception e){
                         System.out.println("error receiving ack packet");
                    }  
                    
                    int opcodereceived =  getInt(Arrays.copyOfRange(ackPacket, 0, 2));
                    int times=0; 
                    
                    if(opcodereceived==5){ // error
                    	System.out.println("ERROR: " +  new String(Arrays.copyOfRange(ackPacket, 4 ,ackPacket.length)));

                    	 try{
                        	UDPPacket = new DatagramPacket(dataPacket, dataPacket.length, IPAddress, TFPTport);
                            clientSocket.send(UDPPacket);    
                         }
                         catch(Exception e){
                             System.out.println("error sending data packet again");
                         }
                    }
                    else if(opcodereceived==4){ // ack
                        int blockreceived = getInt(Arrays.copyOfRange(ackPacket, 2, 4)); 
                       
                        if(blockreceived==65535){ // block is of size 16 bits
                        	blockreceived=-1; // reinit if bigger that max size
                    	}
                        // if block==blockreceive, good keep going to the next iteration
                        // if blockreceive is the previous block, send the packet again
                        if(block==blockreceived+1 && times<NUMBER_OF_TRIES){ // send data packet again if get previous ack
                        	try{
                            	UDPPacket = new DatagramPacket(dataPacket, dataPacket.length, IPAddress, TFPTport);
                                clientSocket.send(UDPPacket);   
                                times++;
                             }
                             catch(Exception e){
                                 System.out.println("error sending data packet again");
                             }      	
                        }                        
                    }
                    else{
                    	sendError(4); 
                    	clientSocket.close();
                    	System.out.println("Try again?");
                    	driver.run();
                    	driver.transfer();
                    }
      
                    counter += DATA_SIZE; 
                }
     }   
}