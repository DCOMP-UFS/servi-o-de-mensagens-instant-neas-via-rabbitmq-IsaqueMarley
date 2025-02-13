package br.ufs.dcomp.ChatRabbitMQ;

import com.rabbitmq.client.*;
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import br.ufs.dcomp.ChatRabbitMQ.MensagemOuterClass.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

public class Chat {
    private static final String HOST = "3.88.226.71";
    private static final String USUARIO = "admin";
    private static final String SENHA = "password";
    private static final String VIRTUAL_HOST = "/";
    private static final String DOWNLOAD_DIR = System.getProperty("user.home") + "/chat/downloads/";

    private static final Map<String, String> grupos = new HashMap<>();

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        System.out.print("user: ");
        String nomeUsuario = scanner.nextLine().trim();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(HOST);
        factory.setUsername(USUARIO);
        factory.setPassword(SENHA);
        factory.setVirtualHost(VIRTUAL_HOST);
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        channel.queueDeclare(nomeUsuario, false, false, false, null);
        channel.queueDeclare(nomeUsuario + "_files", false, false, false, null);
        System.out.println("Filas criadas para: " + nomeUsuario);

        // Thread para receber mensagens de texto
        new Thread(() -> receiveMessages(channel, nomeUsuario)).start();
        
        // Thread para receber arquivos
        new Thread(() -> receiveFiles(channel, nomeUsuario)).start();
        
        String destinatario = null;
        String prompt = ">> ";

        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();

            if (input.startsWith("@")) {
                destinatario = input.substring(1).trim();
                channel.queueDeclare(destinatario, false, false, false, null);
                prompt = "@" + destinatario + ">> ";
            } else if (input.startsWith("!upload")) {
                if (destinatario == null) {
                    System.out.println("Escolha um destinatário antes de enviar um arquivo.");
                    continue;
                }
                String caminhoArquivo = input.split(" ", 2)[1];
                sendFile(channel, nomeUsuario, destinatario, caminhoArquivo);
            } else {
                sendMessage(channel, nomeUsuario, destinatario, input);
            }
        }
    }

    private static void sendMessage(Channel channel, String emissor, String destinatario, String texto) throws IOException {
        Mensagem mensagem = Mensagem.newBuilder()
                .setEmissor(emissor)
                .setData(new SimpleDateFormat("dd/MM/yyyy").format(new Date()))
                .setHora(new SimpleDateFormat("HH:mm").format(new Date()))
                .setConteudo(Conteudo.newBuilder()
                        .setTipo("text/plain")
                        .setCorpo(ByteString.copyFromUtf8(texto)))
                .build();
        channel.basicPublish("", destinatario, null, mensagem.toByteArray());
    }

    private static void sendFile(Channel channel, String emissor, String destinatario, String caminhoArquivo) {
        new Thread(() -> {
            try {
                Path path = Paths.get(caminhoArquivo);
                byte[] conteudoArquivo = Files.readAllBytes(path);
                String tipoMime = Files.probeContentType(path);
                String nomeArquivo = path.getFileName().toString();
                
                System.out.println("Enviando \"" + nomeArquivo + "\" para " + destinatario);
                
                Mensagem mensagem = Mensagem.newBuilder()
                        .setEmissor(emissor)
                        .setData(new SimpleDateFormat("dd/MM/yyyy").format(new Date()))
                        .setHora(new SimpleDateFormat("HH:mm").format(new Date()))
                        .setConteudo(Conteudo.newBuilder()
                                .setTipo(tipoMime)
                                .setNome(nomeArquivo)
                                .setCorpo(ByteString.copyFrom(conteudoArquivo)))
                        .build();
                
                channel.basicPublish("", destinatario + "_files", null, mensagem.toByteArray());
                System.out.println("Arquivo \"" + nomeArquivo + "\" foi enviado para @" + destinatario + "!");
            } catch (IOException e) {
                System.err.println("Erro ao enviar arquivo: " + e.getMessage());
            }
        }).start();
    }

    private static void receiveMessages(Channel channel, String usuario) {
        try {
            Consumer consumer = new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                    try {
                        Mensagem msg = Mensagem.parseFrom(body);
                        System.out.println("(" + msg.getData() + " às " + msg.getHora() + ") " + msg.getEmissor() + " diz: " + msg.getConteudo().getCorpo().toStringUtf8());
                    } catch (InvalidProtocolBufferException e) {
                        e.printStackTrace();
                    }
                }
            };
            channel.basicConsume(usuario, true, consumer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void receiveFiles(Channel channel, String usuario) {
        try {
            Files.createDirectories(Paths.get(DOWNLOAD_DIR));
            Consumer consumer = new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                    try {
                        Mensagem msg = Mensagem.parseFrom(body);
                        String caminhoArquivo = DOWNLOAD_DIR + msg.getConteudo().getNome();
                        Files.write(Paths.get(caminhoArquivo), msg.getConteudo().getCorpo().toByteArray());
                        System.out.println("(" + msg.getData() + " às " + msg.getHora() + ") Arquivo \"" + msg.getConteudo().getNome() + "\" recebido de @" + msg.getEmissor() + "!");
                    } catch (InvalidProtocolBufferException e) {
                        e.printStackTrace();
                    }
                }
            };
            channel.basicConsume(usuario + "_files", true, consumer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
