package spark.examples

import java.io.Serializable
import java.util.Random
import scala.math.sqrt
import cern.jet.math._
import cern.colt.matrix._
import cern.colt.matrix.linalg._
import spark._
import scala.Option

object SparkALS {
  // Parameters set through command line arguments
  var M = 0 // Number of movies
  var U = 0 // Number of users
  var F = 0 // Number of features
  var ITERATIONS = 0

  val LAMBDA = 0.01 // Regularization coefficient

  // Some COLT objects
  val factory2D = DoubleFactory2D.dense
  val factory1D = DoubleFactory1D.dense
  val algebra = Algebra.DEFAULT
  val blas = SeqBlas.seqBlas

  def generateR(): DoubleMatrix2D = {
    val mh = factory2D.random(M, F)
    val uh = factory2D.random(U, F)
    return algebra.mult(mh, algebra.transpose(uh))
  }

  def rmse(targetR: DoubleMatrix2D, ms: Array[DoubleMatrix1D],
    us: Array[DoubleMatrix1D]): Double =
  {
    val r = factory2D.make(M, U)
    for (i <- 0 until M; j <- 0 until U) {
      r.set(i, j, blas.ddot(ms(i), us(j)))
    }
    //println("R: " + r)
    blas.daxpy(-1, targetR, r)
    val sumSqs = r.aggregate(Functions.plus, Functions.square)
    return sqrt(sumSqs / (M * U))
  }

  def update(i: Int, m: DoubleMatrix1D, us: Array[DoubleMatrix1D],
    R: DoubleMatrix2D) : DoubleMatrix1D =
  {
    val U = us.size
    val F = us(0).size
    val XtX = factory2D.make(F, F)
    val Xty = factory1D.make(F)
    // For each user that rated the movie
    for (j <- 0 until U) {
      val u = us(j)
      // Add u * u^t to XtX
      blas.dger(1, u, u, XtX)
      // Add u * rating to Xty
      blas.daxpy(R.get(i, j), u, Xty)
    }
    // Add regularization coefs to diagonal terms
    for (d <- 0 until F) {
      XtX.set(d, d, XtX.get(d, d) + LAMBDA * U)
    }
    // Solve it with Cholesky
    val ch = new CholeskyDecomposition(XtX)
    val Xty2D = factory2D.make(Xty.toArray, F)
    val solved2D = ch.solve(Xty2D)
    return solved2D.viewColumn(0)
  }

  def main(args: Array[String]) {
    var host = ""
    var slices = 0

    (0 to 5).map(i => {
      i match {
        case a if a < args.length => Some(args(a))
        case _ => None
      }
    }).toArray match {
      case Array(host_, m, u, f, iters, slices_) => {
        host = host_ getOrElse "local"
        M = (m getOrElse "100").toInt
        U = (u getOrElse "500").toInt
        F = (f getOrElse "10").toInt
        ITERATIONS = (iters getOrElse "5").toInt
        slices = (slices_ getOrElse "2").toInt
      }
      case _ => {
        System.err.println("Usage: SparkALS [<master> <M> <U> <F> <iters> <slices>]")
        System.exit(1)
      }
    }
    printf("Running with M=%d, U=%d, F=%d, iters=%d\n", M, U, F, ITERATIONS)
    val spark = new SparkContext(host, "SparkALS")
    
    val R = generateR()

    // Initialize m and u randomly
    var ms = Array.fill(M)(factory1D.random(F))
    var us = Array.fill(U)(factory1D.random(F))

    // Iteratively update movies then users
    val Rc  = spark.broadcast(R)
    var msc = spark.broadcast(ms)
    var usc = spark.broadcast(us)
    for (iter <- 1 to ITERATIONS) {
      println("Iteration " + iter + ":")
      ms = spark.parallelize(0 until M, slices)
                .map(i => update(i, msc.value(i), usc.value, Rc.value))
                .toArray
      msc = spark.broadcast(ms) // Re-broadcast ms because it was updated
      us = spark.parallelize(0 until U, slices)
                .map(i => update(i, usc.value(i), msc.value, algebra.transpose(Rc.value)))
                .toArray
      usc = spark.broadcast(us) // Re-broadcast us because it was updated
      println("RMSE = " + rmse(R, ms, us))
      println()
    }

    System.exit(0)
  }
}
