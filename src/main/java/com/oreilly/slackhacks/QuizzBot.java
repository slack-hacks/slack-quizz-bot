package com.oreilly.slackhacks;

import com.ullink.slack.simpleslackapi.SlackAttachment;
import com.ullink.slack.simpleslackapi.SlackChannel;
import com.ullink.slack.simpleslackapi.SlackSession;
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted;
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.*;


public class QuizzBot {

    private static final String TOKEN = "insert your token here";

    private static final Set<String> ACCEPTED_ANSWERS = new HashSet<>(Arrays.asList(new String[]{"1", "2", "3", "4"}));
    private static final int TOTAL_QUIZZ_SIZE = 10;
    private static final int QUESTION_MAX_TIME = 10000;

    private static SlackChannel quizzChannel;
    private static Set<String> answeredUser;
    private static volatile int questionCounter = 0;
    private static int expectedAnswer = 0;
    private static Map<String, Integer> score;
    private static List<Question> allQuestions;
    private static List<Question> shuffledQuestions;
    private static Thread timer;
    private static Object lock = new Object();

    private static class Question {
        String question;
        String answer1;
        String answer2;
        String answer3;
        String answer4;
        int expectedAnswer;
    }

    public static void main(String[] args) throws Exception {
        //loading allQuestions
        allQuestions = loadQuestions();
        //creating the session
        SlackSession session = SlackSessionFactory.createWebSocketSlackSession(TOKEN);
        //adding a message listener to the session
        session.addMessagePostedListener(QuizzBot::processMessagePostedEvent);
        //connecting the session to the Slack team
        session.connect();
        //delegating all the event management to the session
        Thread.sleep(Long.MAX_VALUE);
    }

    private static List<Question> loadQuestions() throws IOException, ParseException {
        JSONParser parser = new JSONParser();
        Reader reader = new InputStreamReader(QuizzBot.class.getResourceAsStream("/questions.json"));
        JSONObject jsonQuestionDefinition = (JSONObject) parser.parse(reader);
        reader.close();
        JSONArray jsonQuestionsArray = (JSONArray) jsonQuestionDefinition.get("questions");
        List<Question> questionList = new ArrayList<>();
        for (JSONObject jsonQuestion : (ArrayList<JSONObject>) jsonQuestionsArray) {
            Question question = new Question();
            question.question = (String) jsonQuestion.get("question");
            question.answer1 = (String) jsonQuestion.get("1");
            question.answer2 = (String) jsonQuestion.get("2");
            question.answer3 = (String) jsonQuestion.get("3");
            question.answer4 = (String) jsonQuestion.get("4");
            question.expectedAnswer = ((Long) jsonQuestion.get("result")).intValue();
            questionList.add(question);
        }
        return questionList;
    }

    private static void processMessagePostedEvent(SlackMessagePosted event, SlackSession session) {
        handleQuizzRequest(event, session);
        handleAnswer(event, session);
    }

    private static void handleQuizzRequest(SlackMessagePosted event, SlackSession session) {
        //looking for !quizz command
        if ("!quizz".equals(event.getMessageContent().trim())) {
            // check if a quizz is currently done on a channel
            if (quizzChannel != null) {
                session.sendMessage(event.getChannel(), "I'm sorry " + event.getSender().getRealName() + ", I'm currently running a quizz, please wait a few minutes");
                return;
            }
            prepareQuizzData(event);
            sendNewQuestion(session);
        }
    }

    private static void prepareQuizzData(SlackMessagePosted event) {
        quizzChannel = event.getChannel();
        questionCounter = 1;
        score = new HashMap<>();
        shuffledQuestions = new ArrayList<>(allQuestions);
        Collections.shuffle(shuffledQuestions);
    }

    private static void sendNewQuestion(SlackSession session) {
        //resetting the users ho have answered to this question
        answeredUser = new HashSet<>();
        Question currentQuestion = shuffledQuestions.get(questionCounter);
        expectedAnswer = currentQuestion.expectedAnswer;
        SlackAttachment attachment = new SlackAttachment(currentQuestion.question, currentQuestion.question, "", "");
        attachment.addField("1", currentQuestion.answer1, true);
        attachment.addField("2", currentQuestion.answer2, true);
        attachment.addField("3", currentQuestion.answer3, true);
        attachment.addField("4", currentQuestion.answer4, true);
        session.sendMessage(quizzChannel, "", attachment);
        timer = buildTimer(session);
        timer.start();
    }

    private static Thread buildTimer(final SlackSession session) {
        return new Thread() {
            @Override
            public void run() {
                try {
                    Thread.sleep(QUESTION_MAX_TIME);
                    int currentCounter = questionCounter;
                    synchronized (lock) {
                        if (questionCounter == currentCounter) {
                            session.sendMessage(quizzChannel, "Time's up");
                            nextQuizzStep(session);
                        }
                    }
                } catch (InterruptedException e) {
                    //interrupted by the good answer
                }
            }
        };
    }

    private static void handleAnswer(SlackMessagePosted event, SlackSession session) {
        //no quizz launched
        if (quizzChannel == null) {
            return;
        }
        //an answer should be given on the quizz channel only
        if (!event.getChannel().getId().equals(quizzChannel.getId())) {
            return;
        }
        //an answer is a single digit from 1 to 4
        String answerValue = event.getMessageContent().trim();
        if (!ACCEPTED_ANSWERS.contains(answerValue)) {
            //ignore answer
            return;
        }
        int currentCounter = questionCounter;
        synchronized (lock) {
            if (questionCounter != currentCounter) {
                // the question has timed out and next one was sent by the timer
                return;
            }
            //A user can answer only once per question
            if (answeredUser.contains(event.getSender().getId())) {
                session.sendMessage(quizzChannel, "I'm sorry " + event.getSender().getRealName() + ", you can only give one answer by question");
                return;
            }
            //This user has now given an answer
            answeredUser.add(event.getSender().getId());
            //Check value
            if (Integer.parseInt(answerValue) == expectedAnswer) {
                //good answer
                goodAnswer(event, session);
            } else {
                wrongAnswer(event, session);
            }
        }
    }

    private static void wrongAnswer(SlackMessagePosted event, SlackSession session) {
        registerPlayerInScoreBoard(event);
        session.sendMessage(quizzChannel, "I'm sorry, you're wrong " + event.getSender().getRealName());
    }

    private static void registerPlayerInScoreBoard(SlackMessagePosted event) {
        Integer currentPlayerScore = score.get(event.getSender().getId());
        if (currentPlayerScore == null) {
            score.put(event.getSender().getId(), 0);
        }
    }

    private static void goodAnswer(SlackMessagePosted event, SlackSession session) {
        timer.interrupt();
        increaseScore(event);
        session.sendMessage(quizzChannel, "Good answer " + event.getSender().getRealName());
        nextQuizzStep(session);
    }

    private static void increaseScore(SlackMessagePosted event) {
        Integer currentPlayerScore = score.get(event.getSender().getId());
        if (currentPlayerScore == null) {
            currentPlayerScore = 0;
        }
        score.put(event.getSender().getId(), currentPlayerScore + 1);
    }

    private static void nextQuizzStep(SlackSession session) {
        questionCounter++;
        if (questionCounter < TOTAL_QUIZZ_SIZE) {
            sendNewQuestion(session);
        } else {
            showResults(session);
        }
    }

    private static void showResults(SlackSession session) {
        SlackAttachment attachment = new SlackAttachment("Final score", "Final score", "", "");
        for (Map.Entry<String, Integer> entry : score.entrySet()) {
            attachment.addField(session.findUserById(entry.getKey()).getRealName(), entry.getValue().toString(), true);
        }
        session.sendMessage(quizzChannel, "", attachment);
        shuffledQuestions = null;
        quizzChannel = null;
    }


}
