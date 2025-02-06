package br.ufs.dcomp.ExemploRabbitMQ;

import com.rabbitmq.client.*;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;

public class Chat {

    private static final String HOST = "44.201.78.252"; // Alterar conforme necessário
    private static final String USUARIO = "admin";    // Alterar conforme necessário
    private static final String SENHA = "password"; // Alterar conforme necessário
    private static final String VIRTUAL_HOST = "/";     // Alterar conforme necessário

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        System.out.print("user: ");
        String nomeUsuario = scanner.nextLine().trim();

        // Configuração da conexão com RabbitMQ
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(HOST);
        factory.setUsername(USUARIO);
        factory.setPassword(SENHA);
        factory.setVirtualHost(VIRTUAL_HOST);
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        // Criar fila do usuário
        channel.queueDeclare(nomeUsuario, false, false, false, null);
        System.out.println("fila usuario criada  " + nomeUsuario);
     String destinatario = null;
        String prompt = ">> ";
        
        
        // Inicia thread para receber mensagens
        new Thread(() -> {
            try {
                Consumer consumer = new DefaultConsumer(channel) {
                     
                    @Override
                    public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                        String message = new String(body, "UTF-8");
                        String sender = properties.getReplyTo();
                        String timestamp = new SimpleDateFormat("dd/MM/yyyy 'às' HH:mm").format(new Date());
                        System.out.println();
                        System.out.println("(" + timestamp + ") " + sender + " diz: " + message);
                        //String prompt = "@" + sender + ">> ";
                        //System.out.print(prompt);
                        //
                    }
                };
                channel.basicConsume(nomeUsuario, true, consumer);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        // Prompt de envio de mensagens
   
        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();
            
        
            

            if (input.startsWith("@")) {
                // Alterar destinatário
                destinatario = input.substring(1).trim();
                channel.queueDeclare(destinatario, false,   false,     false,       null);
                prompt = "@" + destinatario + ">> ";
                System.out.println("fila de destino criada"+destinatario);
                
                

                
            } else if (destinatario != null) {
                // Enviar mensagem para o destinatário atual
                System.out.println("entrou no else if ");
                String message = input;
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .replyTo(nomeUsuario)
                        .build();
                channel.basicPublish("", destinatario, props, message.getBytes("UTF-8"));
                
            } else {
                System.out.println("Escolha um destinatário com @nome");
            }
            
            
            
            
        }
    }
}
