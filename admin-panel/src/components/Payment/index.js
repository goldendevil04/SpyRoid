// Payment.js

import React, { useState } from 'react';
import './Payment.css'; // Import the CSS file for styles

const Payment = () => {
    const [showPopup, setShowPopup] = useState(false);

    const handleContactUsClick = (e) => {
        e.preventDefault();
        setShowPopup(true);
    };

    const closePopup = () => {
        setShowPopup(false);
    };

    return (
        <div className='lhb'>
            <div className="loading-container">
                <img src="./loading.gif" alt="Loading..." width="100%" height="100%" />
                <p className='pmnt'>Payment Due</p>
                <a href="#" className="contact-us" onClick={handleContactUsClick}>Contact Us</a>
            </div>
            {showPopup && (
                <div className="popup">
                    <div className="popup-content">
                        <span className="close" onClick={closePopup}>&times;</span>
                        <h2 style={{fontSize:19}}>Payment</h2>
                        <p style={{fontSize:14}}>Clear your Dues<br/>or<br/>Contact your admin to use our services</p>
                    </div>
                </div>
            )}
        </div>
    );
}

export default Payment;
