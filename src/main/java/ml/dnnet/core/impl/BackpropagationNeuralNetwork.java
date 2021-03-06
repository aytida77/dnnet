package ml.dnnet.core.impl;

import ml.dnnet.commons.data.LabelledData;
import ml.dnnet.commons.data.LabelledDataPoint;
import ml.dnnet.commons.data.UnlabelledData;
import ml.dnnet.commons.transferfunction.TransferFunction;
import ml.dnnet.commons.transferfunction.TransferFunctionFactory;
import ml.dnnet.commons.util.Log;
import ml.dnnet.core.Constants;
import ml.dnnet.core.NeuralNetwork;
import ml.dnnet.core.NeuronLayer;
import ml.dnnet.core.process.BackPropagate;
import ml.dnnet.core.process.FeedForward;
import ml.dnnet.core.process.NumericalGradient;
import ml.dnnet.core.process.Cost;
import org.apache.spark.api.java.JavaRDD;
import org.jblas.DoubleMatrix;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class BackpropagationNeuralNetwork implements NeuralNetwork
{
    static Logger log = Log.getLogger(BackpropagationNeuralNetwork.class);

    private Mode mode;
    private List<Integer> topography;
    public List<NeuronLayer> layers;

    public BackpropagationNeuralNetwork(List<Integer> topography, Mode mode)
    {
        this.mode = mode;
        this.topography = topography;

        initLayers();
    }

    @Override
    public void gradientCheck(LabelledData labelledData) throws Exception
    {
        JavaRDD<LabelledDataPoint> completeData = labelledData.getData();

        long batchSize = completeData.count();

        JavaRDD<List<DoubleMatrix>> numericalDerivativeRDD = completeData.map(new NumericalGradient(layers));
        List<DoubleMatrix> neumericalDerivatives = numericalDerivativeRDD.reduce(new BackPropagate(layers));

        JavaRDD<List<DoubleMatrix>> weightDerivativeRDD = completeData.map(new BackPropagate(layers));
        List<DoubleMatrix> weightDerivatives = weightDerivativeRDD.reduce(new BackPropagate(layers));

        for (int i = 0; i < weightDerivatives.size(); i++)
        {
            log.debug("Derivative (Backpropagation) for Layer " + i + " : " + (weightDerivatives.get(i).mul(1.0 / batchSize)));
            log.debug("Derivative (Numerical) for Layer" + i + "        : " + (neumericalDerivatives.get(i).mul(1.0 / batchSize)) + "\n");
        }
    }

    @Override
    public int train(LabelledData labelledData) throws Exception
    {
        JavaRDD<LabelledDataPoint> completeData = labelledData.getData();
        completeData.cache();

        log.info("Learning Rate = " + Constants.ETA);
        log.info("Maximum Epochs = " + Constants.MAX_EPOCHS + "\n");


        for (int epoch = 1; epoch <= Constants.MAX_EPOCHS; epoch++)
        {
            JavaRDD<LabelledDataPoint> data = completeData.sample(false, 1.0);

            for (int batchIndex = 0; batchIndex < 10; batchIndex++)
            {
                double sampleFraction = 1.0 / (10.0 - batchIndex);
                JavaRDD<LabelledDataPoint> currentBatch = data.sample(false, sampleFraction);
                data = data.subtract(currentBatch);

                long batchSize = currentBatch.count();

                JavaRDD<List<DoubleMatrix>> weightDerivativeRDD = currentBatch.map(new BackPropagate(layers));
                List<DoubleMatrix> weightDerivatives = weightDerivativeRDD.reduce(new BackPropagate(layers));

                for (int i = 0; i < weightDerivatives.size(); i++)
                {
                    DoubleMatrix weightDerivative = weightDerivatives.get(i);
                    weightDerivative.muli((1.0 / batchSize));

                    weightDerivatives.set(i, weightDerivative);

                    DoubleMatrix deltaWeight = weightDerivative.mul((-1.0 * Constants.ETA));
                    layers.get(i).updateWeights(deltaWeight);
                }

                if (isConverged(weightDerivatives))
                {
                    return epoch;
                }
            }

            if (epoch % 10 == 0)
            {
                Cost costCalculator = new Cost(mode, new FeedForward(layers));
                JavaRDD<Double> costRDD = completeData.map(costCalculator);
                List<Double> costList = costRDD.collect();
                double totalCost = 0.0;
                for (Double cost : costList)
                {
                    totalCost += cost;
                }
                log.debug("Completed " + epoch + " iterations; Cost = " + (totalCost / costList.size()));
            }
        }

        return -1;
    }


    @Override
    public LabelledData predict(UnlabelledData unlabelledData) throws Exception
    {
        FeedForward feedForward = new FeedForward(layers);
        JavaRDD<DoubleMatrix> input = unlabelledData.getData();
        JavaRDD<LabelledDataPoint> predicted = input.map(feedForward);
        return new LabelledData(predicted);
    }

    private void initLayers()
    {
        TransferFunction sigmoid = TransferFunctionFactory.sigmoid();

        layers = new ArrayList<>();
        int layerCount = this.topography.size();
        for (int layerIndex = 0; layerIndex < layerCount; layerIndex++)
        {
            NeuronLayer neuronLayer = null;

            // Activation of the final layer depends on the execution mode
            if (layerIndex == layerCount - 1)
            {
                if (this.mode == Mode.REGRESSION)
                {
                    neuronLayer = new NeuronLayer(layerIndex, TransferFunctionFactory.linear(), topography);
                }
                else if (this.mode == Mode.CLASSIFICATION)
                {
                    if (topography.get(layerCount - 1) > 1)
                    {
                        neuronLayer = new NeuronLayer(layerIndex, TransferFunctionFactory.softmax(), topography);
                    }
                    else
                    {
                        neuronLayer = new NeuronLayer(layerIndex, sigmoid, topography);
                    }
                }
            }
            else if (layerIndex == 0)
            {
                neuronLayer = new NeuronLayer(layerIndex, TransferFunctionFactory.linear(), topography);
            }
            else
            {
                neuronLayer = new NeuronLayer(layerIndex, sigmoid, topography);
            }

            layers.add(neuronLayer);
        }
    }

    private boolean isConverged(List<DoubleMatrix> weightDerivatives)
    {
        int count = weightDerivatives.size();
        for (int i = 0; i < count; i++)
        {
            DoubleMatrix weightDerivative = weightDerivatives.get(i);
            for (int row = 0; row < weightDerivative.rows; row++)
            {
                for (int column = 0; column < weightDerivative.columns; column++)
                {
                    double value = Math.abs(weightDerivative.get(row, column));
                    if (value > Constants.WEIGHT_DERIVATIVE_CUTOFF)
                    {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
