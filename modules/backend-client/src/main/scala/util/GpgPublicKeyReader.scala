// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.sso.client.util

import java.io.ByteArrayInputStream
import java.security.PublicKey
import org.bouncycastle.openpgp.operator.jcajce._
import org.bouncycastle.openpgp._
import scala.util.control.NonFatal
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import java.{util => ju}
import org.bouncycastle.bcpg.ArmoredOutputStream
import java.io.ByteArrayOutputStream
import org.http4s.EntityDecoder
import cats.effect.Sync
import org.http4s.DecodeResult
import org.http4s.MalformedMessageBodyFailure
import org.http4s.EntityEncoder

/** Methods to convert between GPG ASCII-amored text and JCA `PublicKey`. */
object GpgPublicKeyReader {

  /**
   * Turn the output from `gpg --armor --export <key>` (GnuPG) or from `armorText` below (BCPG)
   * into a JCA PublicKey.
   */
  def publicKey(pgpArmorText: String): Either[Throwable, PublicKey] =
    try {
      val is = PGPUtil.getDecoderStream(new ByteArrayInputStream(pgpArmorText.getBytes("US-ASCII")))
      val kr = new PGPPublicKeyRingCollection(is, new JcaKeyFingerprintCalculator)
      val pk = kr.iterator.next.getPublicKey
      val kc = new JcaPGPKeyConverter
      Right(kc.getPublicKey(pk))
    } catch {
      case NonFatal(e) => Left(e)
    }

  /** Turn an RSA public key into ASCII-armored text (BCPG) readable by `publicKey` above. */
  def armorText(publicKey: PublicKey): Either[Throwable, String] =
    try {
      val kc   = new JcaPGPKeyConverter
      val pk   = kc.getPGPPublicKey(PublicKeyAlgorithmTags.RSA_SIGN, publicKey, new ju.Date)
      val baos = new ByteArrayOutputStream
      val aos  = new ArmoredOutputStream(baos, new ju.Hashtable())
      pk.encode(aos, true)
      aos.flush()
      aos.close()
      baos.close()
      Right(new String(baos.toByteArray(), "US-ASCII"))
    } catch {
      case NonFatal(e) => Left(e)
    }

  def entityDecoder[F[_]: Sync]: EntityDecoder[F, PublicKey] =
    EntityDecoder.text[F].map(publicKey).flatMapR {
      case Left(err)     => DecodeResult.failure(MalformedMessageBodyFailure("Invalid public key.", Some(err)))
      case Right(pubKey) => DecodeResult.success(pubKey)
    }

  def entityEncoder[F[_]]: EntityEncoder[F, PublicKey] =
    EntityEncoder[F, String].contramap { pubKey =>
      armorText(pubKey) match {
        case Left(err)        => throw err // not great
        case Right(armorText) => armorText
      }
    }

}